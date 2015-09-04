package com.kaltura.hlsplayersdk.cache;

import java.util.Map;

import android.os.Handler;
import android.util.Log;

import com.kaltura.hlsplayersdk.HLSPlayerViewController;
import com.kaltura.hlsplayersdk.events.OnErrorListener;
import com.loopj.android.http.AsyncHttpClient;


/*
 *  The SegmentCacheEntry is a container of SegmentCacheItems. Each SegmentCacheItem
 *  is its own individual download. The SegmentCacheEntry is identified by the primary
 *  url (the first item in the submitted array) when posting events.
 *  
 */



public class SegmentCacheEntry
{
	private SegmentCacheItem [] mItems = null;
	private SegmentCacheEntry selfRef = this;

	public SegmentCacheEntry(String [] uris)
	{
		mItems = new SegmentCacheItem[uris.length];
		for (int i = 0; i < uris.length; ++i)
		{
			mItems[i] = new SegmentCacheItem(this);
			mItems[i].uri = uris[i];			
		}
		
		registerSegmentCachedListener(null, null);
	}
	
	public void setCryptoIds(int [] cryptoIds)
	{
		if (cryptoIds.length != mItems.length) return;
		for (int i = 0; i < cryptoIds.length; ++i)
			mItems[i].cryptoHandle = cryptoIds[i];
	}
	
	public long lastTouchedMillis = 0;
	public long downloadCompletedTime = 0;
	public long downloadStartTime = 0;
	
	
	
	
	public void clear()
	{
		for (int i = 0; i < mItems.length; ++i)
			mItems[i].data = null;
	}
	
	public void cancel()
	{
		for (int i = 0; i < mItems.length; ++i)
			mItems[i].cancel();
	}
	
	public void removeMe(Map<String, SegmentCacheEntry> segmentCache)
	{
		for (int i = 0; i < mItems.length; ++i)
			segmentCache.remove(mItems[i].uri);
	}
	
	public boolean matchUri(String uri)
	{
		for (int i = 0; i < mItems.length; ++i)
			if (mItems[i].uri.equals(uri)) return true;
		return false;
	}
	
	public SegmentCacheItem getItem(String uri)
	{
		for (int i = 0; i < mItems.length; ++i)
			if (mItems[i].uri.equals(uri)) return mItems[i];
		return null;
	}
	
	public void initiateDownload()
	{
		lastTouchedMillis = System.currentTimeMillis();
		downloadStartTime = lastTouchedMillis;
		for (int i = 0; i < mItems.length; ++i)
		{
			final SegmentCacheItem sci = mItems[i];
			initiateDownload(sci);
		}
	}
	
	private void initiateDownload(final SegmentCacheItem sci)
	{
		if (dataSize() != 0) return; // We don't want to initiate a completed download
		sci.running = true;
		sci.downloadStartTime = System.currentTimeMillis();
		Log.i("SegmentCacheEntry", "Requesting: " + sci.uri );
		
		HLSSegmentCache.postToCacheRequestThread( new Runnable()
		{
			@Override
			public void run() {
				AsyncHttpClient httpClient = HLSSegmentCache.httpClient();
				
				if (httpClient == HLSSegmentCache.syncHttpClient) Log.i("HLS Cache", "Using Synchronous HTTP CLient");
				else Log.i("HLS Cache", "Using Asynchronous HTTP Client");
				
				httpClient.setMaxRetriesAndTimeout(0, httpClient.getConnectTimeout());
				sci.request = httpClient.get(HLSSegmentCache.context, sci.uri, new SegmentBinaryResponseHandler(sci));
				Log.i("SegmentCacheEntry", "Requested: " + sci.uri );

			}
		});
	}
	
	public void retry(SegmentCacheItem sce)
	{
		try {
			Thread.sleep(100);
			Thread.yield();
		} catch (InterruptedException e) {
			// Don't care.
		}
		Log.i("SegmentCacheEntry.retry", "retry: " + sce.uri);
		initiateDownload(sce);
	}
	
	public boolean isRunning()
	{
		if (mItems == null) return false;
		
		for (int i = 0; i < mItems.length; ++i)
		{
			if (mItems[i].running)
				return true;
		}
		return false;		
	}
	
	public boolean isWaiting()
	{
		if (mItems == null) return false;
		
		for (int i = 0; i < mItems.length; ++i)
		{
			if (mItems[i].waiting)
				return true;
		}
		return false;
	}
	
	public void setWaiting(boolean waiting)
	{
		for (int i = 0; i < mItems.length; ++i)
			mItems[i].waiting = waiting;
	}
	
	public void setWaiting(String uri, boolean waiting)
	{
		for (int i = 0; i < mItems.length; ++i)
		{
			if (mItems[i].uri.equals(uri))
				mItems[i].waiting = waiting;
		}
	}
	
	public int expectedSize()
	{
		int size = 0;
		for (int i = 0; i < mItems.length; ++i)
			size += mItems[i].expectedSize;
		return size;
	}
	
	public int bytesDownloaded()
	{
		int size = 0;
		for (int i = 0; i < mItems.length; ++i)
			size += mItems[i].bytesDownloaded;
		return size;
	}
	
	public int dataSize(String uri)
	{
		for (SegmentCacheItem item : mItems)
		{
			if (item.uri.equals(uri))
			{
				if (item.data != null)
					return item.data.length;
				return 0;
			}
		}
		return 0;
	}
	
	public int dataSize()
	{
		int ds = 0;
		for (int i = 0; i < mItems.length; ++i)
		{
			if (mItems[i].data != null)
				ds += mItems[i].data.length;
		}
		return ds;
	}
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Count=" + mItems.length);
		for (int i = 0; i < mItems.length; ++i)
		{
			sb.append(" | ");
			sb.append(mItems[i]);
			
		}
		sb.append( " | " + dataSize());
		return sb.toString();			
	}
	
	
	private Handler mCallbackHandler = null;
	private SegmentCachedListener mSegmentCachedListener = null;
	public void registerSegmentCachedListener(SegmentCachedListener listener, Handler callbackHandler)
	{
		synchronized (selfRef)
		{
			if (mSegmentCachedListener != listener)
			{
				Log.i("SegmentCacheEntry", "Setting the SegmentCachedListener to a new value: " + listener + " Old Value: " + mSegmentCachedListener);
				mSegmentCachedListener = listener;
				mCallbackHandler = callbackHandler;
			}
			
			if (mCallbackHandler == null)
			{
				Log.i("SegmentCacheEntry.registerSegmentCachedListener", "CallbackHandler == null");
			}
		}
	}
	
	public void notifySegmentCached()
	{
		HLSSegmentCache.postProgressUpdate(true);
		setWaiting(false);
		if (mSegmentCachedListener != null && mCallbackHandler != null)
		{
			mCallbackHandler.post(new Runnable() {
				SegmentCachedListener listener = mSegmentCachedListener;
				
				public void run()
				{
                    synchronized (HLSSegmentCache.segmentCache)
                    {
                        synchronized (selfRef)
                        {
                            if (listener != null)
                            {
                                String [] uris = new String[mItems.length];
                                for (int i = 0; i < mItems.length; ++i)
                                {
                                    uris[i] = mItems[i].uri;
                                }
                                listener.onSegmentCompleted(uris);
                            }
                        }
                    }
				}
			});
		}
	}
	
	public void postItemSucceeded(SegmentCacheItem item, int statusCode)
	{
		if (statusCode == 200)
		{
			// We're good
			if (!isRunning())
			{
				// We're all done, too!
				downloadCompletedTime = System.currentTimeMillis();
				HLSSegmentCache.notifyStored(this);
			}
		}
		else
		{
			Log.i("SegmentCacheEntry.postItemSucceeded", "status code " + "[" + statusCode + "]" + item.uri);
			if (mSegmentCachedListener != null)
				mSegmentCachedListener.onSegmentFailed(item.uri, statusCode);
			HLSPlayerViewController.currentController.postError(OnErrorListener.MEDIA_ERROR_IO, item.uri + "(" + statusCode + ")");
			
		}
	}
	
	
	public void postItemFailed(SegmentCacheItem item, int statusCode)
	{
		if (mSegmentCachedListener != null)
			mSegmentCachedListener.onSegmentFailed(item.uri, statusCode);
		HLSPlayerViewController.currentController.postError(OnErrorListener.MEDIA_ERROR_IO, item.uri + "(" + statusCode + ")");

	}
	
	public void updateProgress(boolean force)
	{
		// If we have a callback handler, it pretty much means that we're not going to be
		// in a wait state in the SegmentCache <-- does this comment make sense?
		if (mCallbackHandler != null && isWaiting() && bytesDownloaded() != expectedSize())
		{
			HLSSegmentCache.postProgressUpdate(false);
		}
		else if (mCallbackHandler != null && force)
		{
			HLSSegmentCache.postProgressUpdate(true);
		}
	}
}
