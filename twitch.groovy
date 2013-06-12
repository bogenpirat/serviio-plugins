import java.net.URL;

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
 *		<li>V3 (04.02.2013): fixed more escaping, fixed a bug for null-valued
 *			jtv tokens</li>
 *		<li>V2 (03.02.2013): fixed jtv token escaping for serviio linux
 *			installations</li>
 *		<li>V1 (03.02.2013): initial release</li>
 *	</ul></p>
 *
 *	@version 3
 *	@author <a href="irc://irc.quakenet.org/sc2.il">bog</a>
 *
 */

class Twitch extends WebResourceUrlExtractor {
	final Integer VERSION = 3
	final String VALID_FEED_URL = /^https?:\/\/(?:[^\.]*.)?(?:twitch|justin)\.tv\/([a-zA-Z0-9_]+).*$/
	final String TWITCH_API_URL = "http://usher.justin.tv/find/CHANNELNAME.json?type=any&group=&channel_subscription="
	final String TWITCH_SWF_URL = "http://www.justin.tv/widgets/live_embed_player.swf?channel="
	final static Boolean isWindows = System.getProperty("os.name").startsWith("Windows");
	
	int getVersion() {
		return VERSION
	}

	String getExtractorName() {
		return 'twitch.tv'
	}
	
	/**
	 * resolves the URL of the SWF file that would normally play the video.
	 * this is taken from the Location:-header that the server supplies with its
	 * 302 response upon requesting TWITCH_SWF_URL
	 * @param channelName name of the justin.tv/twitch.tv channel
	 */
	String getSwfUrl(String channelName) {
		def url = new URL(TWITCH_SWF_URL + channelName)
		HttpURLConnection con = (HttpURLConnection) url.openConnection()
		con.setInstanceFollowRedirects(false)
		if(con.getResponseCode() % 300 < 100)
			return con.getHeaderField("Location").replaceAll("\\?.*", "")
		else
			return TWITCH_SWF_URL
	}

	boolean extractorMatches(URL feedUrl) {
		return feedUrl ==~ VALID_FEED_URL
	}
	
	WebResourceContainer extractItems(URL resourceUrl, int maxItemsToRetrieve) {
		// let's set some required variables
		def channelName = (String) (resourceUrl =~ VALID_FEED_URL)[0][1] // extract channel name from url
		def live = 1 // for ffmpeg rtmp parameters
		def swfUrl = getSwfUrl(channelName)
		
		// grab and parse the api output and isolate the items
		def jsonText = new URL(TWITCH_API_URL.replaceAll("CHANNELNAME", channelName.toLowerCase())).text
		def json = new JsonSlurper().parseText(jsonText)
		
		def items = [] // prepare list
		
		if(json.size() > 0) // check that api response isn't empty (i.e. stream is offline)
			json.each {
				// it.connect is "rtmp://someip/app", so it already includes the "app" parameter
				def rtmp = it.connect
				def playpath = it.play
				def jtv
				def expiration
				if((it.token == "" || it.token == null) && it.connect ==~ /.*\d+\.\d+\.\d+\.\d+.*/) {
					log "skipping quality ${it.type} because it has no token and requires one" 
					return // skip qualities where we get no token (subscription) and it's a non-cdn server
				} 
				if(it.token != null) {
					// exchange the jtv token's spaces (depending on OS), escape backslashes
					if(isWindows)
						jtv = it.token.replaceAll("\"", "\\\\\"")
					else
						jtv = it.token.replaceAll("\"", "\\\"")
					jtv = jtv.replaceAll(" ", "\\\\20")
					
					expiration = Integer.parseInt((it.token =~ /.*"expiration": (\d+)[^\d].*/)[0][1])
				}
				// a generic string should be enough for identifying purposes
				def title = channelName + "-" + it.type + " [${it.video_height}p]"
				
				items += new WebResourceItem(title: title, additionalInfo: [ 
					expiresImmediately: true,
					cacheKey: title,
					// required parameters: rtmp-url, playpath, swfUrl/Vfy, live, jtv (CDN servers don't need this)
					rtmpUrl: rtmp + " playpath=" + playpath + " swfUrl=" + swfUrl + " swfVfy=1" + ((rtmp ==~ /.*\d+\.\d+\.\d+\.\d+.*/)? " jtv=" + jtv : "") + " live=" + live ])
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
			c.setContentUrl(arg0.additionalInfo.rtmpUrl)
			c.setLive(true)
		}
		return c
	}

	static void main(args) {
		Twitch twitch = new Twitch()

		def items = twitch.extractItems(new URL("http://www.twitch.tv/"+args[0]), 123)
		ContentURLContainer result = twitch.extractUrl(items.getItems()[0], PreferredQuality.HIGH)
		println result
	}
}

