/*
 * Copyright (C) 2010 A. Horn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mcsoxford.rss;

import android.net.Uri;
import android.util.Log;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * HTTP client to retrieve and parse RSS 2.0 feeds. Callers must call
 * {@link RSSReader#close()} to release all resources.
 * 
 * @author Mr Horn
 */
public class RSSReader implements java.io.Closeable {

    private RSSReaderCallbacks mCallbacks;

    public interface RSSReaderCallbacks {
        public abstract boolean onRequestNetworkState();
        public abstract File onRequestCacheFile(String uri);
    }

    public void setCallbacks(RSSReaderCallbacks callbacks) {
        mCallbacks = callbacks;
    }

  /**
   * Thread-safe {@link HttpClient} implementation.
   */
  private final HttpClient httpclient;

  /**
   * Thread-safe RSS parser SPI.
   */
  private final RSSParserSPI parser;

  /**
   * ConnectivityManager to check for network status
   */

  /**
   * Instantiate a thread-safe HTTP client to retrieve RSS feeds. The injected
   * {@link HttpClient} implementation must be thread-safe.
   * 
   * @param httpclient thread-safe HTTP client implementation
   * @param parser thread-safe RSS parser SPI implementation
   */
  public RSSReader(HttpClient httpclient, RSSParserSPI parser) {
    this.httpclient = httpclient;
    this.parser = parser;
  }

  /**
   * Instantiate a thread-safe HTTP client to retrieve RSS feeds. The injected
   * {@link HttpClient} implementation must be thread-safe. Internal memory
   * consumption and load performance can be tweaked with {@link RSSConfig}.
   * 
   * @param httpclient thread-safe HTTP client implementation
   * @param config RSS configuration
   */
  public RSSReader(HttpClient httpclient, RSSConfig config) {
    this(httpclient, new RSSParser(config));
  }

  /**
   * Instantiate a thread-safe HTTP client to retrieve and parse RSS feeds.
   * Internal memory consumption and load performance can be tweaked with
   * {@link RSSConfig}.
   */
  public RSSReader(RSSConfig config) {
    this(new DefaultHttpClient(), new RSSParser(config));
  }

  /**
   * Instantiate a thread-safe HTTP client to retrieve and parse RSS feeds.
   * Default RSS configuration capacity values are used.
   */
  public RSSReader() {
    this(new DefaultHttpClient(), new RSSParser(new RSSConfig()));
  }

  
  public static final int NETWORK_DISCONNECTED = 0;
  public static final int NETWORK_CONNECTED = 1;
  
  /**
   * Send HTTP GET request and parse the XML response to construct an in-memory
   * representation of an RSS 2.0 feed.
   * 
   * @param uri RSS 2.0 feed URI
   * @return in-memory representation of downloaded RSS feed
   * @throws RSSReaderException if RSS feed could not be retrieved because of
   *           HTTP error
   * @throws RSSFault if an unrecoverable IO error has occurred
   */
  public RSSFeed load(String uri) throws RSSReaderException {
	
	  RSSFeed feed = null;

      boolean isConnected = true;
      if(mCallbacks != null) {
          isConnected = mCallbacks.onRequestNetworkState();
      }
	
	  if(isConnected) {
		  
		  // Connected to network, attempt to get feed from URI
		  
		  final HttpGet httpget = new HttpGet(uri);
	
		  InputStream feedStream = null;
		  try {
		      // Send GET request to URI
		      Log.i("TAG", "sending get request");
		      final HttpResponse response = httpclient.execute(httpget);
		
		      // Check if server response is valid
		      Log.i("TAG", "checking if server response is valid");
		      final StatusLine status = response.getStatusLine();
		      if (status.getStatusCode() != HttpStatus.SC_OK) {
		    	  throw new RSSReaderException(status.getStatusCode(),
		    			  status.getReasonPhrase());
		      }
	
		      // Extract content stream from HTTP response
		      HttpEntity entity = response.getEntity();
		      feedStream = entity.getContent();
	
		      if(feedStream != null) {
		    	  
		    	  // Good input stream, proceed normally
		    	  
		          ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		    	  // Fake code simulating the copy
		    	  // You can generally do better with nio if you need...
		    	  // And please, unlike me, do something about the Exceptions :D
		    	  byte[] buffer = new byte[1024];
		    	  int len;
		    	  while ((len = feedStream.read(buffer)) > -1 ) {
		    		  baos.write(buffer, 0, len);
		    	  }
		    	  baos.flush();
		          
		    	  // Create a copy of input stream for writing to cache
		    	  InputStream is1 = new ByteArrayInputStream(baos.toByteArray()); 
		    	  InputStream is2 = new ByteArrayInputStream(baos.toByteArray());
		    	  feed = parser.parse(is1);
		    	  saveToCache(getCacheFile(uri), is2);
		      } else {
		    	  // Bad input stream, attempt to use cached feed
		    	  feed = getCachedFeed(getCacheFile(uri));
		      }
		  } catch (ClientProtocolException e) {
			  throw new RSSFault(e);
		  } catch (IOException e) {
			  throw new RSSFault(e);
		  } finally {
			  Resources.closeQuietly(feedStream);
		  }
	  } else {
		  // Network connection not usable, attempt to get cached feed
		  feed = getCachedFeed(getCacheFile(uri));
	  }
	  
	  // Set feed link
      if(feed != null) {
	      if (feed.getLink() == null) {
	    	  feed.setLink(Uri.parse(uri));
	      }
      }
	  
	  return feed;
  }

  private File getCacheFile(String uri) {

      if (mCallbacks == null) {
          return null;
      } else {
          return mCallbacks.onRequestCacheFile(uri);
      }
  }

  /**
   * Get a RSSFeed from a cached file
   * 
   * @param cacheFile file to parse feed from
   * @return RSSFeed parsed from cacheFile
   */
  private RSSFeed getCachedFeed(File cacheFile) {
	  
	  if(cacheFile == null)
		  return null;
	  
	  RSSFeed feed = null;
	  
	  try {
		  
		  InputStream is = new FileInputStream(cacheFile);
		  
		  if(is != null)
			  feed = parser.parse(is);
		  
	  } catch (FileNotFoundException e) {
		  e.printStackTrace();
	  }
	  
	  return feed;
  }
  
  /**
   * Writes an InputStream to a cache File for offline use.
   * 
   * @param cacheFile File to write feedStream to
   * @param feedStream InputStream to write to cache file
   * @throws IOException if file write fails
   */
  private void saveToCache(File cacheFile, InputStream feedStream) throws IOException {

	  if(cacheFile == null || feedStream == null)
		  return;
	  
      // FileOutputStream used to write file
      FileOutputStream fileOutput = new FileOutputStream(cacheFile);
	  
	  // Buffer to read data from InputStream
	  byte[] buffer = new byte[1024];
	  int bufferLength = 0;
	  while ( (bufferLength = feedStream.read(buffer)) > 0 ) 
	  {
		  fileOutput.write(buffer, 0, bufferLength);	
	  }
	
	  //close the output stream when done
	  fileOutput.close();
  }
  
  /**
   * Release all HTTP client resources.
   */
  public void close() {
	  httpclient.getConnectionManager().shutdown();
  }

}

