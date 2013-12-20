import java.net.URL;
import java.net.URLEncoder

import org.serviio.library.metadata.*
import org.serviio.library.online.*
import groovy.json.*

/**
 *	<h1>twitch/justin.tv Serviio plugin</h1>
 *
 *	<h2>Usage instructions</h2>
 *	<p>Add streams as a <strong>Web Resource</strong> with 
 *	"<i>http://www.twitch.tv/CHANNELNAME</i>" as URL.</p>
 *
 *	<h2>VERSION HISTORY</h2>
 *	<p><ul>
 *		<li>V9 (20.12.2013): removed RTMP streams since they are now disabled
 *			and will likely be defunct forever.</li>
 *		<li>V8 (14.12.2013): simplified HLS/mobile grabbing, fixed a bug from
 *			V5 that broke rtmpurl generation, updated swfUrl</li>
 *		<li>V7 (11.12.2013): changed mobile stream grabber to also get source 
 *			quality</li>
 *		<li>V6 (09.12.2013): added support for displaying mobile streams</li>
 *		<li>V5 (11.08.2013): worked around some pointless twitch api output,
 *			fixed a bug with transcoding</li>
 *		<li>V4 (16.06.2013): worked around bug-inducing twitch swf 
 *			redirection</li>
 *		<li>V3 (04.02.2013): fixed more escaping, fixed a bug for null-valued
 *			jtv tokens</li>
 *		<li>V2 (03.02.2013): fixed jtv token escaping for serviio linux
 *			installations</li>
 *		<li>V1 (03.02.2013): initial release</li>
 *	</ul></p>
 *
 *	@version 9
 *	@author <a href="https://twitter.com/bogenpirat">bog</a>
 *
 */

class Twitch extends WebResourceUrlExtractor {
	final Integer VERSION = 9
	final String VALID_FEED_URL = /^https?:\/\/(?:[^\.]*.)?(?:twitch|justin)\.tv\/([a-zA-Z0-9_]+).*$/ // TODO
	final String TWITCH_HLS_API_PLAYLIST_URL = "http://usher.twitch.tv/select/CHANNELNAME.json?allow_source=true&nauthsig=&nauth=&type=any"
	final static Boolean isWindows = System.getProperty("os.name").startsWith("Windows");
	
	int getVersion() {
		return VERSION
	}

	String getExtractorName() {
		return 'twitch.tv'
	}

	boolean extractorMatches(URL feedUrl) {
		return feedUrl ==~ VALID_FEED_URL
	}
	
	WebResourceContainer extractItems(URL resourceUrl, int maxItemsToRetrieve) {
		def channelName = (String) (resourceUrl =~ VALID_FEED_URL)[0][1] // extract channel name from url
		
		def items = [] // prepare list
		
		/////////////////////////
		// HLS VIDEO INTERFACE //
		/////////////////////////
		def playlist = new URL(TWITCH_HLS_API_PLAYLIST_URL.
			replaceAll("CHANNELNAME", channelName.toLowerCase())).text
		
		def m = playlist =~ /(?s)NAME="([^"]*)".*?BANDWIDTH=(\d+).*?(http:\/\/.+?)[\n\r]/
		
		while(m.find()) {
			// a generic string should be enough for identifying purposes
			def title = channelName + "-hls" + " [${m.group(1)}/${(Float.parseFloat(m.group(2))/1024) as Integer}K]"
			items += new WebResourceItem(title: title, additionalInfo: [ 
				expiresImmediately: true,
				cacheKey: title,
				url: m.group(3) ])
		}
		
		
		// create and fill the container
		def container = new WebResourceContainer()
		container.setTitle(channelName)
		container.setItems(items)
		
		return container
	}

	ContentURLContainer extractUrl(WebResourceItem arg0, PreferredQuality arg1) {
		def c = new ContentURLContainer()
		if(arg0 != null) {
			c.setExpiresImmediately(true)
			c.setCacheKey(arg0.additionalInfo.cacheKey)
			c.setContentUrl(arg0.additionalInfo.url)
			c.setLive(true)
		}
		return c
	}

	static void main(args) {
		Twitch twitch = new Twitch()

		twitch.extractItems(new URL("http://www.twitch.tv/"+args[0]), 123).getItems().each { it->
			ContentURLContainer result = twitch.extractUrl(it, PreferredQuality.HIGH)
			println result
		}
	}
}

