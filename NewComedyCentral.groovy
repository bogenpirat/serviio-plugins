import java.net.URL;
import java.net.URLEncoder

import org.serviio.library.metadata.*
import org.serviio.library.online.*
import groovy.json.*

/**
 *	<h1>The New Comedy Central Serviio plugin</h1>
 *
 *	<h2>Usage instructions</h2>
 *	<p>Add streams as a <strong>Web Resource</strong> with
 *	"<i>http://thecolbertreport.cc.com/full-episodes/...</i>" or
 *	"<i>http://thedailyshow.cc.com/full-episodes/...</i>" or
 *	"<i>http://tosh.comedycentral.com/episodes/...</i>" or
 *	"<i>http://beta.southparkstudios.com/full-episodes/...</i>" or
 *	"<i>http://www.cc.com/episodes/...</i>" 
 *	as URL.</p>
 *
 *	<h2>VERSION HISTORY</h2>
 *	<p><ul>
 *		<li>V1 (25.03.2014): initial release</li>
 *	</ul></p>
 *
 *	@version 1
 *	@author <a href="https://twitter.com/bogenpirat">bog</a>
 *
 */

class NewComedyCentral extends WebResourceUrlExtractor {
	final Integer VERSION = 1
	
	def VALID_FEED_URL = ~"http://www.cc.com/episodes/.*|http://beta.southparkstudios.com/full-episodes/.*|http://thedailyshow.cc.com/full-episodes/.*|http://thecolbertreport.cc.com/full-episodes/.*|http://tosh.comedycentral.com/episodes/.*"
	def REGEX_MGID = ~/data-mgid="([^"]+)"/
	def REGEX_ACTS = ~/<media:content[^>]*medium="video"[^>]*url="([^"]+)"[^>]*>/
	def REGEX_QUALITIES = ~/<rendition[^>]*width="([^"]+)"[^>]*height="([^"]+)"[^>]*bitrate="([^"]+)"[^>]*>\s*<src>([^<]+)<\/src>\s*<\/rendition>/
	def REGEX_RTMPTOHTTP = ~/^rtmpe?:\/\/.*?\/(gsp\.comedystor\/.*)/
	def REGEX_TITLE = ~/<meta property="og:title" content="([^"]+)"\/>/
	
	def mrssUrl = "http://thedailyshow.cc.com/feeds/mrss?uri=%s"
	def httpUrl = "http://mtvnmobile.vo.llnwd.net/kip0/_pxn=1+_pxI0=Ripod-h264+_pxL0=undefined+_pxM0=+_pxK=18639+_pxE=mp4/44620/mtvnorigin/"
	
	final static Boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
	
	int getVersion() {
		return VERSION
	}

	String getExtractorName() {
		return 'NewComedyCentral'
	}

	boolean extractorMatches(URL feedUrl) {
		return (feedUrl =~ VALID_FEED_URL).find()
	}
	
	WebResourceContainer extractItems(URL resourceUrl, int maxItemsToRetrieve) {
		// prepare list of qualities/acts
		def segments = [:]
		
		// extract mgid and title
		def mgid, title
		def m = resourceUrl.text =~ REGEX_MGID
		if(m.find()) {
			mgid = m.group(1)
		}
		m = resourceUrl.text =~ REGEX_TITLE
		if(m.find()) {
			title = m.group(1)
		}
		
		// extract act/quality urls
		m = new URL(String.format(mrssUrl, URLEncoder.encode(mgid))).text =~ REGEX_ACTS
		
		while(m.find()) {
			def actUrl = new URL(m.group(1))
		
			def qualitiesXml = actUrl.text
			def m2 = qualitiesXml =~ REGEX_QUALITIES
			def qualityId = "", oldQualityId = ""
			while(m2.find()) {
				qualityId = "${m2.group(2)}p@${m2.group(3)}K"
				
				if(oldQualityId.equals(qualityId))
					continue
				
				if(segments[qualityId] == null) segments[qualityId] = []
				def m3, segUrl
				if((m3 = m2.group(4) =~ REGEX_RTMPTOHTTP).find()) {
					segUrl = httpUrl + m3.group(1)
				}
				segments[qualityId] << segUrl
				
				oldQualityId = qualityId
			}
		}
		
		// reverse quality order so we get the best atop, and create web containers
		def items = []
		segments.reverseEach { quality, val ->
			def concatUrls = val.join("|")
			def myUrl = isWindows ? "\"concat:${concatUrls}\"" : "concat:${concatUrls}"
			items << new WebResourceItem(title: "[$quality] " + title, additionalInfo: [
				expiresImmediately: false,
				cacheKey: title,
				url: myUrl ])
		}
		
		// create and fill the container
		def container = new WebResourceContainer()
		container.setTitle(title)
		container.setItems(items)
		
		return container
	}

	ContentURLContainer extractUrl(WebResourceItem arg0, PreferredQuality arg1) {
		def c = new ContentURLContainer()
		
		if(arg0 != null) {
			c.setExpiresImmediately(false)
			c.setCacheKey(arg0.additionalInfo.cacheKey)
			c.setContentUrl(arg0.additionalInfo.url)
			c.setLive(false)
		}
		
		return c
	}

	static void main(args) {
		NewComedyCentral cc = new NewComedyCentral()

		cc.extractItems(new URL(args[0]), 123).getItems().each { it ->
			ContentURLContainer result = cc.extractUrl(it, PreferredQuality.HIGH)
			println result
		}
	}
}
