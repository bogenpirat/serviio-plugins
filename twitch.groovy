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
 *		<li>V12 (22.09.2014): fixed VODs; now displaying as segments.</li>
 *		<li>V11 (24.01.2014): fixed stream grabbing.</li>
 *		<li>V10 (15.01.2014): added support for VODs.</li>
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
 *	@version 12
 *	@author <a href="https://twitter.com/bogenpirat">bog</a>
 *
 */

class Twitch extends WebResourceUrlExtractor {
	final Integer VERSION = 12
	final String VALID_FEED_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/([a-zA-Z0-9_]+).*\$"
	final String VALID_VOD_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/([a-zA-Z0-9_]+)/(b|c)/(\\d+)[^\\d]*\$"
	final String TWITCH_HLS_API_PLAYLIST_URL = "http://usher.twitch.tv/select/%s.json?nauthsig=%s&nauth=%s&allow_source=true"
	final String TWITCH_VOD_API_URL = "http://api.justin.tv/api/broadcast/by_archive/%d.json?onsite=true"
	final String TWITCH_ACCESSTOKEN_API = "http://api.twitch.tv/api/channels/%s/access_token"
	final String TWITCH_STREAM_API = "https://api.twitch.tv/kraken/streams/%s"
	final String TWITCH_VODID_CDATA_STRING = "PP\\.archive_id = \"(\\d+)\";"
	final static Boolean isWindows = System.getProperty("os.name").startsWith("Windows");
	
	int getVersion() {
		return VERSION
	}

	String getExtractorName() {
		return 'twitch.tv'
	}

	boolean extractorMatches(URL feedUrl) {
		return (feedUrl ==~ VALID_FEED_URL) || (feedUrl ==~ VALID_VOD_URL)
	}
	
	WebResourceContainer extractItems(URL resourceUrl, int maxItemsToRetrieve) {
		def items, title
		def channelName = (String) (resourceUrl =~ VALID_FEED_URL)[0][1] // extract channel name from url
		
		if(resourceUrl ==~ VALID_VOD_URL) {
			def urlKind = (resourceUrl =~ VALID_VOD_URL)[0][2] // "b" or "c"
			def vodId
			
			if(urlKind.equals("b")) {
				vodId = (resourceUrl =~ VALID_VOD_URL)[0][3] as Integer
			} else if(urlKind.equals("c")) {
				vodId = (resourceUrl.text =~ TWITCH_VODID_CDATA_STRING)[0][1] as Integer
			}
			
			title = "${channelName} VOD ${vodId}"
			items = extractVods(vodId)
		} else if(resourceUrl ==~ VALID_FEED_URL) { // it's a stream
			title = "${channelName} Stream"
			items = extractHlsStream(channelName)
		}
		
		// create and fill the container
		def container = new WebResourceContainer()
		container.setTitle(title)
		container.setItems(items)
		
		return container
	}
	
	List<WebResourceItem> extractVods(Integer vodId) {
		def json = new JsonSlurper().parseText(new URL(String.format(TWITCH_VOD_API_URL, vodId)).text)
		def title
		
		// collect segment data first
		def segments = [ "Source": [] ]
		for(def jsonSegment : json) {
			title = jsonSegment["title"] // always the same, but too lazy to have it stop after the first assignment
			segments["Source"] << jsonSegment["video_file_url"]
			for(def transcodeSegment : jsonSegment["transcode_file_urls"].entrySet()) {
				def qualityName = (transcodeSegment.getKey() =~ /transcode_(.+)/)[0][1]
				def segmentUrl = transcodeSegment.getValue()
				
				if(!segments.containsKey(qualityName))
					segments[qualityName] = []
				
				segments[qualityName] << segmentUrl
			}
		}
		
		// assemble webresourceitem list
		def items = []
		segments.each { quality, val ->
			def ptNr = 1
			val.each { segment ->
				items += new WebResourceItem(title: "[${quality}, ${ptNr}/${val.size()}] " + title, additionalInfo: [
					expiresImmediately: false,
					cacheKey: title,
					url: segment ])
				ptNr++
			}
		}
		
		return items
	}
	
	List<WebResourceItem> extractHlsStream(String channelName) {
		def items = [] // prepare list
		
		def tokenJson = new JsonSlurper().parseText(new URL(String.format(TWITCH_ACCESSTOKEN_API, channelName.toLowerCase())).text)
		def token = tokenJson.token
		def sig = tokenJson.sig
		
		//getting stream thubnail
		def streamJson = new JsonSlurper().parseText(new URL(String.format(TWITCH_STREAM_API, channelName.toLowerCase())).text)
		def thumbnailUrl
		if (streamJson.stream) {
			thumbnailUrl = streamJson.stream.preview.medium
		}
		
		def playlist = new URL(String.format(TWITCH_HLS_API_PLAYLIST_URL, channelName.toLowerCase(), sig, token)).text
		
		def m = playlist =~ /(?s)NAME="([^"]*)".*?BANDWIDTH=(\d+).*?(http:\/\/.+?)[\n\r]/
		
		while(m.find()) {
			// a generic string should be enough for identifying purposes
			def title = channelName + "-hls" + " [${m.group(1)}/${(Float.parseFloat(m.group(2))/1024) as Integer}K]"
			items += new WebResourceItem(title: title, additionalInfo: [
				expiresImmediately: true,
				cacheKey: title,
				url: m.group(3),
				thumbnailUrl: thumbnailUrl ])
		}
		
		return items
	}

	ContentURLContainer extractUrl(WebResourceItem arg0, PreferredQuality arg1) {
		def c = new ContentURLContainer()
		if(arg0 != null) {
			c.setExpiresImmediately(arg0.additionalInfo.url.indexOf("concat:") != -1 ? false : true)
			c.setCacheKey(arg0.additionalInfo.cacheKey)
			c.setContentUrl(arg0.additionalInfo.url)
			c.setLive(arg0.additionalInfo.url.indexOf("concat:") != -1 ? false : true)
			c.setThumbnailUrl(arg0.additionalInfo.thumbnailUrl)
		}
		return c
	}

	static void main(args) {
		Twitch twitch = new Twitch()
		def url = ""

		if(!args[0].contains("http"))
			url = "http://www.twitch.tv/" + args[0]
		else
			url = args[0]
		
		twitch.extractItems(new URL(url), 123).getItems().each { it->
			ContentURLContainer result = twitch.extractUrl(it, PreferredQuality.HIGH)
			println result
		}
	}
}
