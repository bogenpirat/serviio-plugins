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
 *		<li>V17 (15.09.2016): some API calls now require a Client ID field</li>
 *		<li>V16 (02.04.2016): urls are now https only - fixed</li>
 *		<li>V15 (04.02.2015): added support for /v/ vods</li>
 *		<li>V14 (18.12.2014): even newer API urls (Author: ivanmalm)</li>
 *		<li>V13 (02.12.2014): newer api urls used, vod extraction fixed
 *			(courtesy of commandercool).</li>
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
 *	@version 17
 *	@author <a href="https://twitter.com/bogenpirat">bog</a>
 *
 */

class Twitch extends WebResourceUrlExtractor {
	final String CLIENT_ID = "jzkbprff40iqj646a697cyrvl0zt2m6"
	final Integer VERSION = 17
	final String VALID_FEED_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/([a-zA-Z0-9_]+).*\$"
	final String VALID_VOD_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/([a-zA-Z0-9_]+)/(b|c)/(\\d+)[^\\d]*\$"
	final String VALID_HLS_VOD_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/([a-zA-Z0-9_]+)/v/(\\d+)[^\\d]*\$"
	final String TWITCH_HLS_API_PLAYLIST_URL = "http://usher.twitch.tv/api/channel/hls/%s.m3u8?sig=%s&token=%s&allow_source=true"
	final String TWITCH_VOD_API_URL = "https://api.twitch.tv/api/videos/%s%s?client_id=${CLIENT_ID}"
	final String TWITCH_HLS_VOD_API_URL = "http://usher.twitch.tv/vod/%s?nauth=%s&nauthsig=%s"
	final String TWITCH_VOD_API_INFO = "https://api.twitch.tv/kraken/videos/%s%s?client_id=${CLIENT_ID}"
	final String TWITCH_ACCESSTOKEN_API = "https://api.twitch.tv/api/channels/%s/access_token?client_id=${CLIENT_ID}"
	final String TWITCH_HLSVOD_ACCESSTOKEN_API = "https://api.twitch.tv/api/vods/%s/access_token?as3=t&client_id=${CLIENT_ID}"
	final String TWITCH_STREAM_API = "https://api.twitch.tv/kraken/streams/%s?client_id=${CLIENT_ID}"

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
			vodId = (resourceUrl =~ VALID_VOD_URL)[0][3] as Integer
			title = "${channelName} VOD ${vodId}"
			items = extractVods(vodId, urlKind)
		} else if(resourceUrl ==~ VALID_HLS_VOD_URL) {
			def vodId = (resourceUrl =~ VALID_HLS_VOD_URL)[0][2] as Integer
			title = "${channelName} VOD ${vodId}"
			items = extractHlsVods(vodId)
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
	
	List<WebResourceItem> extractHlsVods(Integer vodId) {
		def info = new JsonSlurper().parseText(new URL(String.format(TWITCH_VOD_API_INFO, "v", vodId)).text)
		def vodTitle = info.title
		def preview = info.preview
		
		def auth = new JsonSlurper().parseText(new URL(String.format(TWITCH_HLSVOD_ACCESSTOKEN_API, vodId)).text)
		
		def playlist = new URL(String.format(TWITCH_HLS_VOD_API_URL, vodId, URLEncoder.encode(auth.token), URLEncoder.encode(auth.sig))).text
		
		def m = playlist =~ /(?s)NAME="([^"]*)".*?BANDWIDTH=(\d+).*?(http:\/\/.+?)[\n\r]/
		
		def items = []
		while(m.find()) {
			// a generic string should be enough for identifying purposes
			def title = vodTitle + " [${m.group(1)}/${(Float.parseFloat(m.group(2))/1024) as Integer}K]"
			items += new WebResourceItem(title: title, additionalInfo: [
				expiresImmediately: true,
				cacheKey: title,
				url: m.group(3),
				thumbnailUrl: preview,
				live: true
				])
		}
		
		return items
	}
	
	List<WebResourceItem> extractVods(Integer vodId, String urlKind) {
		def type
		// type can be 'b' or 'a' depending on urlKind
		if (urlKind == "b") {
			type = "a"
		} else {
			type = "c"
		}
		def info = new JsonSlurper().parseText(new URL(String.format(TWITCH_VOD_API_INFO, type, vodId)).text)
		def title = info.title
		def preview = info.preview
		
		def json = new JsonSlurper().parseText(new URL(String.format(TWITCH_VOD_API_URL, type, vodId)).text)
		
		def items = []
		json.chunks.each { chunk, part ->
			def ptNr = 1
			part.each { data ->
				items += new WebResourceItem(title: "[${chunk}, ${ptNr}/${part.size()}] " + title, additionalInfo: [
				expiresImmediately: false,
				cacheKey: title,
				url: data.url,
				thumbnailUrl: preview,
				live: false
				])
				ptNr++
			}
		}		
		return items
	}
	
	List<WebResourceItem> extractHlsStream(String channelName) {
		def items = [] // prepare list
		
		def auth = new JsonSlurper().parseText(new URL(String.format(TWITCH_ACCESSTOKEN_API, channelName.toLowerCase())).text)
		
		//getting stream thubnail
		def streamJson = new JsonSlurper().parseText(new URL(String.format(TWITCH_STREAM_API, channelName.toLowerCase())).text)
		def thumbnailUrl
		if (streamJson.stream) {
			thumbnailUrl = streamJson.stream.preview.medium
		}
		
		def playlist = new URL(String.format(TWITCH_HLS_API_PLAYLIST_URL, channelName.toLowerCase(), auth.sig, auth.token)).text
		
		def m = playlist =~ /(?s)NAME="([^"]*)".*?BANDWIDTH=(\d+).*?(http:\/\/.+?)[\n\r]/
		
		while(m.find()) {
			// a generic string should be enough for identifying purposes
			def title = channelName + "-hls" + " [${m.group(1)}/${(Float.parseFloat(m.group(2))/1024) as Integer}K]"
			items += new WebResourceItem(title: title, additionalInfo: [
				expiresImmediately: true,
				cacheKey: title,
				url: m.group(3),
				thumbnailUrl: thumbnailUrl,
				live: true
				])
		}
		
		return items
	}

	ContentURLContainer extractUrl(WebResourceItem arg0, PreferredQuality arg1) {
		def c = new ContentURLContainer()
		if(arg0 != null) {
			c.setExpiresImmediately(arg0.additionalInfo.expiresImmediately)
			c.setCacheKey(arg0.additionalInfo.cacheKey)
			c.setContentUrl(arg0.additionalInfo.url)
			c.setLive(arg0.additionalInfo.live)
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
