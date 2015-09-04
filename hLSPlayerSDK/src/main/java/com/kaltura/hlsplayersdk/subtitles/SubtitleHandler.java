package com.kaltura.hlsplayersdk.subtitles;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import com.kaltura.hlsplayersdk.cache.HLSSegmentCache;
import com.kaltura.hlsplayersdk.manifest.ManifestParser;
import com.kaltura.hlsplayersdk.manifest.ManifestPlaylist;
import com.kaltura.hlsplayersdk.manifest.ManifestReloader.ManifestGetHandler;

import android.util.Log;

public class SubtitleHandler implements OnSubtitleParseCompleteListener, ManifestParser.ReloadEventListener, ManifestGetHandler {

	private ManifestParser mManifest;
	private double mLastTime = 0;
	private int lastLanguage = 0;
	
	public SubtitleHandler(ManifestParser baseManifest)
	{
		mManifest = baseManifest;
		initialize();
	}
	
	public void initialize()
	{
		Thread t = new Thread(new Runnable() {
			@Override
			public void run()
			{
				ManifestParser man = getManifestForLanguage(lastLanguage);
				if (man != null && !man.streamEnds && man.subtitles.size() > 0)
				{
					double accum = 0.0;
					man.subtitles.get(0).load();
					for (int m = 0; m < man.subtitles.size(); ++m)
					{
						accum = man.subtitles.get(m).setTimeWindowStart(accum);
						accum += man.subtitles.get(m).segmentTimeWindowDuration;
					}
				}
			}
		}, "Subtitle Initialize Thread");
		t.start();

	}
	
	public boolean hasSubtitles()
	{
		boolean rval = mManifest.subtitles.size() > 0;
		if (!rval) rval = mManifest.subtitlePlayLists.size() > 0;			
		return rval;
	}
	
	public List<String> getLanguageList()
	{
		List<String> languages = new ArrayList<String>();
		if (mManifest.subtitlePlayLists.size() > 0)
		{
			for (int i = 0; i < mManifest.subtitlePlayLists.size(); ++i)
			{
				languages.add(mManifest.subtitlePlayLists.get(i).language);
			}
		}
		return languages;
	}
	
	public String[] getLanguages()
	{
		if (mManifest.subtitlePlayLists.size() > 0)
		{
			String[] languages = new String[mManifest.subtitlePlayLists.size()];
			for (int i = 0; i < mManifest.subtitlePlayLists.size(); ++i)
			{
				languages[i] = mManifest.subtitlePlayLists.get(i).language;
			}
			return languages;
		}
		return null;
	}
	
	public int getDefaultLanguageIndex()
	{
		for (int i = 0; i < mManifest.subtitlePlayLists.size(); ++i)
		{
			if (mManifest.subtitlePlayLists.get(i).isDefault)
				return i;
		}
		return 0;		
	}
	
	public int getLanguageCount()
	{
		if (mManifest.subtitlePlayLists.size() > 0) return mManifest.subtitlePlayLists.size();
		if (mManifest.subtitles.size() > 0) return 1;
		return 0;
	}
	
	public Vector<TextTrackCue> update(double time, int language)
	{
		SubTitleSegment stp = getSegmentForTime(time, language);
		
		if (stp != null)
		{
			if (!stp.isLoaded())
				stp.load();
			
			Vector<TextTrackCue> cues = stp.getCuesForTimeRange(mLastTime, time);
			mLastTime = time;
			
			if (stp.inPrecacheWindow(time, 10))
			{
				precacheSegmentAtTime(time + 10, language);
			}
			
			for (TextTrackCue cue : cues)
				Log.i("SubtitleHandler.update", "Returning:" + cue);
			
			return cues;
		}
		return null;
	}
	
	public void precacheSegmentAtTime(double time, int language)
	{
		SubTitleSegment ntsp = getSegmentForTime(time, language);
		if (ntsp != null)
		{
			ntsp.precache();
		}
	}
	
	private SubTitleSegment getSegmentForTime(double time, int language)
	{
		if (mManifest == null)
		{
			Log.w("SubtitleHandler", "Manifest is null. Subtitles will not display.");
			return null;
		}

		ManifestParser mp = getManifestForLanguage(language);
		
		if (mp == null)
		{
			Log.w("SubtitleHandler", "Could not find subtitle playlist for the current language. Subtitles will not display.");
			return null;
		}
		
		for (int i = 0; i < mp.subtitles.size(); ++i)
		{
			SubTitleSegment stp = mp.subtitles.get(i);
			if (stp != null)
			{
				if ( stp.timeInSegment(time))
				{
					//Log.i("SubtitleHandler.getSegmentForTime", "Returning segment " + i + " for time " + time + ". Window = " + stp.segmentTimeWindowStart + "-->" + (stp.segmentTimeWindowStart + stp.segmentTimeWindowDuration));
					return stp;
				}
			}
		}
		return null;

	}
	
	ManifestParser getManifestForLanguage(int language)
	{
		if (language < 0) return null;
		
		ManifestParser mp = null;
		if (mManifest.subtitlePlayLists.size() > language)
		{
			
			ManifestPlaylist mpl = mManifest.subtitlePlayLists.get(language);
			if (mpl.manifest != null && mpl.manifest.subtitles.size() > 0)
			{
				mp = mpl.manifest;
				mp.quality = language;
			}
		}
		else if (mManifest.subtitles.size() > 0)
		{
			mp = mManifest;
			mp.quality = language;
		}
		return mp;
	}
	
	private void mergeManifests(ManifestParser manifest, ManifestParser newManifest)
	{
		SubTitleSegment lastSeg = manifest.subtitles.get(manifest.subtitles.size() - 1);
		
		for (SubTitleSegment seg : newManifest.subtitles)
		{
			if (seg.id > lastSeg.id)
			{
				seg.setTimeWindowStart(lastSeg.segmentTimeWindowStart + lastSeg.segmentTimeWindowDuration);
				manifest.subtitles.add(seg);
				lastSeg = seg;
			}
		}
	}
	
	private void updateManifestForLanguage(ManifestParser manifest, int language)
	{
		if (language < 0) return; // no point.
		
		if (mManifest.subtitlePlayLists.size() > language)
		{
			ManifestPlaylist mpl = mManifest.subtitlePlayLists.get(language);
			ManifestParser oldMan = mpl.manifest;
			if (oldMan != null && manifest != null)
			{
				mergeManifests(oldMan, manifest);
			}
		}
	}

	@Override
	public void onSubtitleParserComplete(SubTitleSegment segment) {
		
	}
	
	/****************************************
	
	RELOADING
	
	*****************************************/
	
	private ManifestParser reloadingManifest = null;
	private Timer reloadTimer = null;
	private int reloadingLanguage = -1;
	private boolean closed = false;
	private long mTimerDelay = 10000;
	
	public void stopReloads()
	{
		if (reloadingManifest != null)
		{
			reloadingManifest.setReloadEventListener(null);
			reloadingManifest = null;
		}
		killTimer();
	}
	
	public void close()
	{
		closed = true;
	}
	
	private void killTimer()
	{
		if (reloadTimer != null)
		{
			reloadTimer.cancel();
			reloadTimer = null;
		}
	}
	
	private void reload(int language, ManifestParser manifest)
	{
		if (closed) return;
		killTimer(); // In case the timer is active - don't want to do another reload in the middle of it
		
		reloadingLanguage = language;
		
		ManifestParser manToReload = manifest != null ? manifest : getManifestForLanguage(reloadingLanguage);
		if (manToReload != null)
		{
			manToReload.reload(this);
		}
	}
	
	private void startReloadTimer(final int language)
	{
		if (closed) return;
		killTimer();
		
		reloadTimer = new Timer();
		reloadTimer.schedule(new TimerTask()
		{
			public void run()
			{
				Log.i("SubtitleHandler.reloadTimerComplete.run", "Reload Timer Complete!");
				reload(language, null);
			}
		}, mTimerDelay);
	}
	

	@Override
	public void onReloadComplete(ManifestParser parser)
	{
		if (parser == null || parser.getReloadChild() == null)
			return; // A strange failure of the reloader
		
		ManifestParser newMan = parser.getReloadChild();
		
		updateManifestForLanguage(newMan, newMan.quality);
	}

	@Override
	public void onReloadFailed(ManifestParser parser)
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public ManifestParser getVideoManifestToReload()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ManifestParser getAltAudioManifestToReload()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ManifestParser getSubtitleManifestToReload()
	{
		// TODO Auto-generated method stub
		return getManifestForLanguage(lastLanguage);
	}
}
