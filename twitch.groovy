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
 *		<li>V18 (16.02.2021): updated for new APIs</li>
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
	final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
	final String CLIENT_ID_API = "hssx1bgogbpcukaz4xf2g18syu2ied"
	final String OAUTH_TOKEN = "qu8nj7ez1nm3189tpspqciev8lyk3t"
	final Integer VERSION = 18
	final String VALID_FEED_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/([a-zA-Z0-9_]+).*\$"
	final String VALID_HLS_VOD_URL = "^https?://(?:[^\\.]*.)?twitch\\.tv/videos/(\\d+)[^\\d]*\$"
	final String TWITCH_HLS_API_PLAYLIST_URL = "http://usher.twitch.tv/api/channel/hls/%s.m3u8?sig=%s&token=%s&allow_source=true"
	final String TWITCH_VOD_API_URL = "https://api.twitch.tv/api/videos/%s%s?client_id=${CLIENT_ID}"
	final String TWITCH_HLS_VOD_API_URL = "https://usher.ttvnw.net/vod/%s.m3u8?allow_source=true&sig=%s&supported_codecs=avc1&token=%s&cdm=wv"
	final String TWITCH_VOD_API_INFO = "https://api.twitch.tv/helix/videos?id=%s"
	final String TWITCH_GQL_API = "https://gql.twitch.tv/gql"
	final String TWITCH_HLSVOD_ACCESSTOKEN_API = "https://api.twitch.tv/api/vods/%s/access_token?as3=t&client_id=${CLIENT_ID}"
	final String TWITCH_STREAM_API = "https://api.twitch.tv/helix/streams?user_login=%s"
	final def reqProps = [ 'Authorization': 'Bearer ' + OAUTH_TOKEN, 'Client-Id': CLIENT_ID_API ]
	final String TWITCH_GQL_LIVE_ACCESS_TOKEN_PAYLOAD = JsonOutput.toJson([
		"operationName":"PlaybackAccessToken_Template",
		"query":"query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) {  streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) {    value    signature    __typename  }  videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) {    value    signature    __typename  }}",
		"variables":[
			"isLive":true,
			"login":"%s",
			"isVod":false,
			"vodID":"",
			"playerType":"site"
		]
	])
	final String TWITCH_GQL_VOD_ACCESS_TOKEN_PAYLOAD = JsonOutput.toJson([
		"operationName":"PlaybackAccessToken_Template",
		"query":"query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) {  streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) {    value    signature    __typename  }  videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) {    value    signature    __typename  }}",
		"variables":[
			"isLive":false,
			"login":"",
			"isVod":true,
			"vodID":"%s",
			"playerType":"site"
		]
	])


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
		
		if(resourceUrl ==~ VALID_HLS_VOD_URL) {
			def vodId = (resourceUrl =~ VALID_HLS_VOD_URL)[0][1] as Integer
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
		def info = new JsonSlurper().parseText(new URL(String.format(TWITCH_VOD_API_INFO, vodId)).getText(requestProperties: reqProps))
		def vodTitle = info.data[0].title
		def preview = info.data[0].thumbnail_url.replace('%{width}', '1920').replace('%{height}', '1080')
		
		// grab auth token
		def message = String.format(TWITCH_GQL_VOD_ACCESS_TOKEN_PAYLOAD, vodId)
		def conn = new URL(TWITCH_GQL_API).openConnection()
		conn.setRequestMethod("POST")
		conn.setRequestProperty('Client-ID', CLIENT_ID)
		conn.setRequestProperty('User-Agent', 'curl/7.68.0')
		conn.setRequestProperty('Authorization', "Bearer ${OAUTH_TOKEN}")
		conn.setDoOutput(true)
		conn.getOutputStream().write(message.getBytes("UTF-8"))
		
		def t = conn.getInputStream().getText()
		def auth = new JsonSlurper().parseText(t)
		
		def playlist = new URL(String.format(TWITCH_HLS_VOD_API_URL, vodId, URLEncoder.encode(auth.data.videoPlaybackAccessToken.signature), URLEncoder.encode(auth.data.videoPlaybackAccessToken.value))).text
		
		def m = playlist =~ /(?s)NAME="([^"]*)".*?BANDWIDTH=(\d+).*?(https?:\/\/.+?)[\n\r]/
		
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
		
		// grab auth token
		def message = String.format(TWITCH_GQL_LIVE_ACCESS_TOKEN_PAYLOAD, channelName)
		def conn = new URL(TWITCH_GQL_API).openConnection()
		conn.setRequestMethod("POST")
		conn.setRequestProperty('Client-ID', CLIENT_ID)
		conn.setRequestProperty('User-Agent', 'curl/7.68.0')
		conn.setRequestProperty('Authorization', "Bearer ${OAUTH_TOKEN}")
		conn.setDoOutput(true)
		conn.getOutputStream().write(message.getBytes("UTF-8"))
		
		def t = conn.getInputStream().getText()
		def auth = new JsonSlurper().parseText(t)
		
		//getting stream thubnail
		def streamJson = new JsonSlurper().parseText(new URL(String.format(TWITCH_STREAM_API, channelName.toLowerCase())).getText(requestProperties: reqProps))
		def thumbnailUrl
		if (streamJson.stream) {
			thumbnailUrl = streamJson.stream.preview.medium
		}
		
		def playlist = new URL(String.format(TWITCH_HLS_API_PLAYLIST_URL, channelName.toLowerCase(), auth.data.streamPlaybackAccessToken.signature, auth.data.streamPlaybackAccessToken.value)).text
		
		def m = playlist =~ /(?s)NAME="([^"]*)".*?BANDWIDTH=(\d+).*?(https?:\/\/.+?)[\n\r]/
		
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
