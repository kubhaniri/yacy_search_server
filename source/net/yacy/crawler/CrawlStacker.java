// plasmaCrawlStacker.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
//
// This file was contributed by Martin Thelian
// ([MC] removed all multithreading and thread pools, this is not necessary here; complete renovation 2007)
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.crawler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.yacy.contentcontrol.ContentControlFilterUpdateThread;
import net.yacy.cora.document.ASCII;
import net.yacy.cora.document.analysis.Classification.ContentDomain;
import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.cora.document.UTF8;
import net.yacy.cora.order.Base64Order;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.ftp.FTPClient;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.crawler.data.CrawlQueues;
import net.yacy.crawler.data.NoticedURL;
import net.yacy.crawler.data.ResultURLs;
import net.yacy.crawler.data.ZURL;
import net.yacy.crawler.data.ResultURLs.EventOrigin;
import net.yacy.crawler.data.ZURL.FailCategory;
import net.yacy.crawler.retrieval.FTPLoader;
import net.yacy.crawler.retrieval.HTTPLoader;
import net.yacy.crawler.retrieval.Request;
import net.yacy.crawler.retrieval.SMBLoader;
import net.yacy.crawler.robots.RobotsTxt;
import net.yacy.kelondro.data.citation.CitationReference;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.rwi.IndexCell;
import net.yacy.kelondro.workflow.WorkflowProcessor;
import net.yacy.peers.SeedDB;
import net.yacy.repository.Blacklist.BlacklistType;
import net.yacy.repository.FilterEngine;
import net.yacy.search.Switchboard;
import net.yacy.search.index.Segment;

public final class CrawlStacker {
    
    public static String ERROR_NO_MATCH_MUST_MATCH_FILTER = "url does not match must-match filter ";
    public static String ERROR_MATCH_WITH_MUST_NOT_MATCH_FILTER = "url matches must-not-match filter ";
    

    private final ConcurrentLog log = new ConcurrentLog("STACKCRAWL");
    private final RobotsTxt robots;
    private final WorkflowProcessor<Request>  requestQueue;
    private final CrawlQueues       nextQueue;
    private final CrawlSwitchboard  crawler;
    private final Segment           indexSegment;
    private final SeedDB            peers;
    private final boolean           acceptLocalURLs, acceptGlobalURLs;
    private final FilterEngine      domainList;

    // this is the process that checks url for double-occurrences and for allowance/disallowance by robots.txt

    public CrawlStacker(
            final RobotsTxt robots,
            final CrawlQueues cq,
            final CrawlSwitchboard cs,
            final Segment indexSegment,
            final SeedDB peers,
            final boolean acceptLocalURLs,
            final boolean acceptGlobalURLs,
            final FilterEngine domainList) {
        this.robots = robots;
        this.nextQueue = cq;
        this.crawler = cs;
        this.indexSegment = indexSegment;
        this.peers = peers;
        this.acceptLocalURLs = acceptLocalURLs;
        this.acceptGlobalURLs = acceptGlobalURLs;
        this.domainList = domainList;
        this.requestQueue = new WorkflowProcessor<Request>("CrawlStacker", "This process checks new urls before they are enqueued into the balancer (proper, double-check, correct domain, filter)", new String[]{"Balancer"}, this, "job", 10000, null, WorkflowProcessor.availableCPU);
        this.log.info("STACKCRAWL thread initialized.");
    }


    public int size() {
        return this.requestQueue.getQueueSize();
    }
    public boolean isEmpty() {
        if (!this.requestQueue.queueIsEmpty()) return false;
        return true;
    }

    public void clear() {
        this.requestQueue.clear();
    }

    public void announceClose() {
        this.log.info("Flushing remaining " + size() + " crawl stacker job entries.");
        this.requestQueue.shutdown();
    }

    public synchronized void close() {
        this.log.info("Shutdown. waiting for remaining " + size() + " crawl stacker job entries. please wait.");
        this.requestQueue.shutdown();

        this.log.info("Shutdown. Closing stackCrawl queue.");

        clear();
    }

    public Request job(final Request entry) {
        // this is the method that is called by the busy thread from outside
        if (entry == null) return null;

        // record the link graph for this request
        byte[] anchorhash = entry.url().hash();
        IndexCell<CitationReference> urlCitationIndex = this.indexSegment.urlCitation();
        if (urlCitationIndex != null && entry.referrerhash() != null) try {
            urlCitationIndex.add(anchorhash, new CitationReference(entry.referrerhash(), entry.appdate().getTime()));
        } catch (final Exception e) {
            ConcurrentLog.logException(e);
        }
        
        try {
            final String rejectReason = stackCrawl(entry);

            // if the url was rejected we store it into the error URL db
            if (rejectReason != null) {
                this.nextQueue.errorURL.push(entry, ASCII.getBytes(this.peers.mySeed().hash), new Date(), 1, FailCategory.FINAL_LOAD_CONTEXT, rejectReason, -1);
            }
        } catch (final Exception e) {
            CrawlStacker.this.log.warn("Error while processing stackCrawl entry.\n" + "Entry: " + entry.toString() + "Error: " + e.toString(), e);
            return null;
        }
        return null;
    }

    public void enqueueEntry(final Request entry) {

        // DEBUG
        if (this.log.isFinest()) this.log.finest("ENQUEUE " + entry.url() + ", referer=" + entry.referrerhash() + ", initiator=" + ((entry.initiator() == null) ? "" : ASCII.String(entry.initiator())) + ", name=" + entry.name() + ", appdate=" + entry.appdate() + ", depth=" + entry.depth());
        this.requestQueue.enQueue(entry);
    }
    public void enqueueEntriesAsynchronous(final byte[] initiator, final String profileHandle, final Map<DigestURI, Properties> hyperlinks) {
        new Thread() {
            @Override
            public void run() {
                Thread.currentThread().setName("enqueueEntriesAsynchronous");
                enqueueEntries(initiator, profileHandle, hyperlinks, true);
            }
        }.start();
    }

    private void enqueueEntries(final byte[] initiator, final String profileHandle, final Map<DigestURI, Properties> hyperlinks, final boolean replace) {
        for (final Map.Entry<DigestURI, Properties> e: hyperlinks.entrySet()) {
            if (e.getKey() == null) continue;

            // delete old entry, if exists to force a re-load of the url (thats wanted here)
            final DigestURI url = e.getKey();
            final byte[] urlhash = url.hash();
            if (replace) {
                this.indexSegment.fulltext().remove(urlhash);
                this.nextQueue.urlRemove(urlhash);
                String u = url.toNormalform(true);
                if (u.endsWith("/")) {
                    u = u + "index.html";
                } else if (!u.contains(".")) {
                    u = u + "/index.html";
                }
                try {
                    final byte[] uh = new DigestURI(u).hash();
                    this.indexSegment.fulltext().remove(uh);
                    this.nextQueue.noticeURL.removeByURLHash(uh);
                    this.nextQueue.errorURL.remove(uh);
                } catch (final MalformedURLException e1) {}
            }

            if (url.getProtocol().equals("ftp")) {
                // put the whole ftp site on the crawl stack
                enqueueEntriesFTP(initiator, profileHandle, url.getHost(), url.getPort(), replace);
            } else {
                // put entry on crawl stack
                enqueueEntry(new Request(
                        initiator,
                        url,
                        null,
                        e.getValue().getProperty("name", ""),
                        new Date(),
                        profileHandle,
                        0,
                        0,
                        0,
                        0
                        ));
            }
        }
    }

    public void enqueueEntriesFTP(final byte[] initiator, final String profileHandle, final String host, final int port, final boolean replace) {
        final CrawlQueues cq = this.nextQueue;
        new Thread() {
            @Override
            public void run() {
                Thread.currentThread().setName("enqueueEntriesFTP");
                BlockingQueue<FTPClient.entryInfo> queue;
                try {
                    queue = FTPClient.sitelist(host, port);
                    FTPClient.entryInfo entry;
                    while ((entry = queue.take()) != FTPClient.POISON_entryInfo) {

                        // delete old entry, if exists to force a re-load of the url (thats wanted here)
                        DigestURI url = null;
                        try {
                            url = new DigestURI("ftp://" + host + (port == 21 ? "" : ":" + port) + MultiProtocolURI.escape(entry.name));
                        } catch (final MalformedURLException e) {
                            continue;
                        }
                        final byte[] urlhash = url.hash();
                        if (replace) {
                            CrawlStacker.this.indexSegment.fulltext().remove(urlhash);
                            cq.noticeURL.removeByURLHash(urlhash);
                            cq.errorURL.remove(urlhash);
                        }

                        // put entry on crawl stack
                        enqueueEntry(new Request(
                                initiator,
                                url,
                                null,
                                MultiProtocolURI.unescape(entry.name),
                                entry.date,
                                profileHandle,
                                0,
                                0,
                                0,
                                entry.size
                                ));
                    }
                } catch (final IOException e1) {
                } catch (final InterruptedException e) {
                }
            }
        }.start();
    }

    /**
     * simple method to add one url as crawljob
     * @param url
     * @return null if successfull, a reason string if not successful
     */
    public String stackSimpleCrawl(final DigestURI url) {
    	final CrawlProfile pe = this.crawler.defaultSurrogateProfile;
    	return stackCrawl(new Request(
                this.peers.mySeed().hash.getBytes(),
                url,
                null,
                "CRAWLING-ROOT",
                new Date(),
                pe.handle(),
                0,
                0,
                0,
                0
                ));
    }

    /**
     * stacks a crawl item. The position can also be remote
     * @param entry
     * @return null if successful, a reason string if not successful
     */
    public String stackCrawl(final Request entry) {
        //this.log.logFinest("stackCrawl: nexturlString='" + nexturlString + "'");

        final CrawlProfile profile = this.crawler.getActive(UTF8.getBytes(entry.profileHandle()));
        String error;
        if (profile == null) {
            error = "LOST STACKER PROFILE HANDLE '" + entry.profileHandle() + "' for URL " + entry.url();
            this.log.warn(error);
            return error;
        }

        error = checkAcceptance(entry.url(), profile, entry.depth());
        if (error != null) return error;

        // store information
        final boolean local = Base64Order.enhancedCoder.equal(entry.initiator(), UTF8.getBytes(this.peers.mySeed().hash));
        final boolean proxy = (entry.initiator() == null || entry.initiator().length == 0 || ASCII.String(entry.initiator()).equals("------------")) && profile.handle().equals(this.crawler.defaultProxyProfile.handle());
        final boolean remote = profile.handle().equals(this.crawler.defaultRemoteProfile.handle());
        final boolean global =
            (profile.remoteIndexing()) /* granted */ &&
            (entry.depth() == profile.depth()) /* leaf node */ &&
            //(initiatorHash.equals(yacyCore.seedDB.mySeed.hash)) /* not proxy */ &&
            (
                    (this.peers.mySeed().isSenior()) ||
                    (this.peers.mySeed().isPrincipal())
            ) /* qualified */;

        if (!local && !global && !remote && !proxy) {
            error = "URL '" + entry.url().toString() + "' cannot be crawled. initiator = " + ((entry.initiator() == null) ? "" : ASCII.String(entry.initiator())) + ", profile.handle = " + profile.handle();
            this.log.severe(error);
            return error;
        }

        long maxFileSize = Long.MAX_VALUE;
        if (!entry.isEmpty()) {
            final String protocol = entry.url().getProtocol();
            if (protocol.equals("http") || protocol.equals("https")) maxFileSize = Switchboard.getSwitchboard().getConfigLong("crawler.http.maxFileSize", HTTPLoader.DEFAULT_MAXFILESIZE);
            if (protocol.equals("ftp")) maxFileSize = Switchboard.getSwitchboard().getConfigLong("crawler.ftp.maxFileSize", FTPLoader.DEFAULT_MAXFILESIZE);
            if (protocol.equals("smb")) maxFileSize = Switchboard.getSwitchboard().getConfigLong("crawler.smb.maxFileSize", SMBLoader.DEFAULT_MAXFILESIZE);
        }

        // check availability of parser and maxfilesize
        String warning = null;
        if ((maxFileSize >= 0 && entry.size() > maxFileSize) ||
            entry.url().getContentDomain() == ContentDomain.APP  ||
            entry.url().getContentDomain() == ContentDomain.IMAGE  ||
            entry.url().getContentDomain() == ContentDomain.AUDIO  ||
            entry.url().getContentDomain() == ContentDomain.VIDEO ||
            entry.url().getContentDomain() == ContentDomain.CTRL) {
            warning = this.nextQueue.noticeURL.push(NoticedURL.StackType.NOLOAD, entry, profile, this.robots);
            //if (warning != null && this.log.isFine()) this.log.logFine("CrawlStacker.stackCrawl of URL " + entry.url().toNormalform(true, false) + " - not pushed: " + warning);
            return null;
        }

        if (global) {
            // it may be possible that global == true and local == true, so do not check an error case against it
            if (proxy) this.log.warn("URL '" + entry.url().toString() + "' has conflicting initiator properties: global = true, proxy = true, initiator = proxy" + ", profile.handle = " + profile.handle());
            if (remote) this.log.warn("URL '" + entry.url().toString() + "' has conflicting initiator properties: global = true, remote = true, initiator = " + ASCII.String(entry.initiator()) + ", profile.handle = " + profile.handle());
            warning = this.nextQueue.noticeURL.push(NoticedURL.StackType.GLOBAL, entry, profile, this.robots);
        } else if (local) {
            if (proxy) this.log.warn("URL '" + entry.url().toString() + "' has conflicting initiator properties: local = true, proxy = true, initiator = proxy" + ", profile.handle = " + profile.handle());
            if (remote) this.log.warn("URL '" + entry.url().toString() + "' has conflicting initiator properties: local = true, remote = true, initiator = " + ASCII.String(entry.initiator()) + ", profile.handle = " + profile.handle());
            warning = this.nextQueue.noticeURL.push(NoticedURL.StackType.LOCAL, entry, profile, this.robots);
        } else if (proxy) {
            if (remote) this.log.warn("URL '" + entry.url().toString() + "' has conflicting initiator properties: proxy = true, remote = true, initiator = " + ASCII.String(entry.initiator()) + ", profile.handle = " + profile.handle());
            warning = this.nextQueue.noticeURL.push(NoticedURL.StackType.LOCAL, entry, profile, this.robots);
        } else if (remote) {
            warning = this.nextQueue.noticeURL.push(NoticedURL.StackType.REMOTE, entry, profile, this.robots);
        }
        if (warning != null && this.log.isFine()) this.log.fine("CrawlStacker.stackCrawl of URL " + entry.url().toNormalform(true) + " - not pushed: " + warning);

        return null;
    }

    public String checkAcceptance(final DigestURI url, final CrawlProfile profile, final int depth) {

        // check if the protocol is supported
        final String urlProtocol = url.getProtocol();
        final String urlstring = url.toString();
        if (!Switchboard.getSwitchboard().loader.isSupportedProtocol(urlProtocol)) {
            this.log.severe("Unsupported protocol in URL '" + urlstring + "'.");
            return "unsupported protocol";
        }

        // check if ip is local ip address
        final String urlRejectReason = urlInAcceptedDomain(url);
        if (urlRejectReason != null) {
            if (this.log.isFine()) this.log.fine("denied_(" + urlRejectReason + ")");
            return "denied_(" + urlRejectReason + ")";
        }

        // check blacklist
        if (Switchboard.urlBlacklist.isListed(BlacklistType.CRAWLER, url)) {
            this.log.fine("URL '" + urlstring + "' is in blacklist.");
            return "url in blacklist";
        }

        // filter with must-match for URLs
        if ((depth > 0) && !profile.urlMustMatchPattern().matcher(urlstring).matches()) {
            if (this.log.isFine()) this.log.fine("URL '" + urlstring + "' does not match must-match crawling filter '" + profile.urlMustMatchPattern().toString() + "'.");
            return ERROR_NO_MATCH_MUST_MATCH_FILTER + profile.urlMustMatchPattern().toString();
        }

        // filter with must-not-match for URLs
        if ((depth > 0) && profile.urlMustNotMatchPattern().matcher(urlstring).matches()) {
            if (this.log.isFine()) this.log.fine("URL '" + urlstring + "' matches must-not-match crawling filter '" + profile.urlMustNotMatchPattern().toString() + "'.");
            return ERROR_MATCH_WITH_MUST_NOT_MATCH_FILTER + profile.urlMustNotMatchPattern().toString();
        }

        // deny cgi
        if (url.isIndividual() && !profile.crawlingQ())  { // TODO: make special property for crawlingIndividual
            if (this.log.isFine()) this.log.fine("URL '" + urlstring + "' is CGI URL.");
            return "individual url (sessionid etc) not wanted";
        }

        // deny post properties
        if (url.isPOST() && !profile.crawlingQ())  {
            if (this.log.isFine()) this.log.fine("URL '" + urlstring + "' is post URL.");
            return "post url not allowed";
        }

        // check if the url is double registered
        final HarvestProcess dbocc = this.nextQueue.urlExists(url.hash()); // returns the name of the queue if entry exists
        final Date oldDate = this.indexSegment.fulltext().getLoadDate(ASCII.String(url.hash()));
        if (oldDate == null) {
            if (dbocc != null) {
                // do double-check
                if (dbocc == HarvestProcess.ERRORS) {
                    final ZURL.Entry errorEntry = this.nextQueue.errorURL.get(url.hash());
                    return "double in: errors (" + errorEntry.anycause() + ")";
                }
                return "double in: " + dbocc.toString();
            }
        } else {
            final boolean recrawl = profile.recrawlIfOlder() > oldDate.getTime();
            if (recrawl) {
                if (this.log.isInfo())
                    this.log.info("RE-CRAWL of URL '" + urlstring + "': this url was crawled " +
                        ((System.currentTimeMillis() - oldDate.getTime()) / 60000 / 60 / 24) + " days ago.");
            } else {
                if (dbocc == null) {
                    return "double in: LURL-DB, oldDate = " + oldDate.toString();
                }
                if (this.log.isInfo()) this.log.info("URL '" + urlstring + "' is double registered in '" + dbocc.toString() + "'. " + "Stack processing time:");
                if (dbocc == HarvestProcess.ERRORS) {
                    final ZURL.Entry errorEntry = this.nextQueue.errorURL.get(url.hash());
                    return "double in: errors (" + errorEntry.anycause() + "), oldDate = " + oldDate.toString();
                }
                return "double in: " + dbocc.toString() + ", oldDate = " + oldDate.toString();
            }
        }

        // deny urls that exceed allowed number of occurrences
        final int maxAllowedPagesPerDomain = profile.domMaxPages();
        if (maxAllowedPagesPerDomain < Integer.MAX_VALUE && maxAllowedPagesPerDomain > 0) {
            final AtomicInteger dp = profile.getCount(url.getHost());
            if (dp != null && dp.get() >= maxAllowedPagesPerDomain) {
                if (this.log.isFine()) this.log.fine("URL '" + urlstring + "' appeared too often in crawl stack, a maximum of " + maxAllowedPagesPerDomain + " is allowed.");
                return "crawl stack domain counter exceeded";
            }

            if (ResultURLs.domainCount(EventOrigin.LOCAL_CRAWLING, url.getHost()) >= maxAllowedPagesPerDomain) {
                if (this.log.isFine()) this.log.fine("URL '" + urlstring + "' appeared too often in result stack, a maximum of " + maxAllowedPagesPerDomain + " is allowed.");
                return "result stack domain counter exceeded";
            }
        }

        // the following filters use a DNS lookup to check if the url matches with IP filter
        // this is expensive and those filters are check at the end of all other tests

        // filter with must-match for IPs
        if ((depth > 0) && profile.ipMustMatchPattern() != CrawlProfile.MATCH_ALL_PATTERN && url.getHost() != null && !profile.ipMustMatchPattern().matcher(url.getInetAddress().getHostAddress()).matches()) {
            if (this.log.isFine()) this.log.fine("IP " + url.getInetAddress().getHostAddress() + " of URL '" + urlstring + "' does not match must-match crawling filter '" + profile.ipMustMatchPattern().toString() + "'.");
            return "ip " + url.getInetAddress().getHostAddress() + " of url does not match must-match filter";
        }

        // filter with must-not-match for IPs
        if ((depth > 0) && profile.ipMustNotMatchPattern() != CrawlProfile.MATCH_NEVER_PATTERN && url.getHost() != null && profile.ipMustNotMatchPattern().matcher(url.getInetAddress().getHostAddress()).matches()) {
            if (this.log.isFine()) this.log.fine("IP " + url.getInetAddress().getHostAddress() + " of URL '" + urlstring + "' matches must-not-match crawling filter '" + profile.ipMustNotMatchPattern().toString() + "'.");
            return "ip " + url.getInetAddress().getHostAddress() + " of url matches must-not-match filter";
        }

        // filter with must-match for IPs
        final String[] countryMatchList = profile.countryMustMatchList();
        if (depth > 0 && countryMatchList != null && countryMatchList.length > 0) {
            final Locale locale = url.getLocale();
            if (locale != null) {
                final String c0 = locale.getCountry();
                boolean granted = false;
                matchloop: for (final String c: countryMatchList) {
                    if (c0.equals(c)) {
                        granted = true;
                        break matchloop;
                    }
                }
                if (!granted) {
                    if (this.log.isFine()) this.log.fine("IP " + url.getInetAddress().getHostAddress() + " of URL '" + urlstring + "' does not match must-match crawling filter '" + profile.ipMustMatchPattern().toString() + "'.");
                    return "country " + c0 + " of url does not match must-match filter for countries";
                }
            }
        }

        return null;
    }


    /**
     * Test a url if it can be used for crawling/indexing
     * This mainly checks if the url is in the declared domain (local/global)
     * @param url
     * @return null if the url can be accepted, a string containing a rejection reason if the url cannot be accepted
     */
    public String urlInAcceptedDomain(final DigestURI url) {
        // returns true if the url can be accepted according to network.unit.domain
        if (url == null) return "url is null";
        // check domainList from network-definition
        if(this.domainList != null) {
        	if(!this.domainList.isListed(url, null)) {
        		return "the url '" + url + "' is not in domainList of this network";
        	}
        }
        
        if (Switchboard.getSwitchboard().getConfigBool(
				"contentcontrol.enabled", false) == true) {

			if (!Switchboard.getSwitchboard()
					.getConfig("contentcontrol.mandatoryfilterlist", "")
					.equals("")) {
				FilterEngine f = ContentControlFilterUpdateThread.getNetworkFilter();
				if (f != null) {
					if (!f.isListed(url, null)) {

						return "the url '"
								+ url
								+ "' does not belong to the network mandatory filter list";

					}
				}
			}

		}
        
        final boolean local = url.isLocal();
        if (this.acceptLocalURLs && local) return null;
        if (this.acceptGlobalURLs && !local) return null;
        final String host = url.getHost();
        if (host == null) return "url.host is null";
        // check if this is a local address and we are allowed to index local pages:
        //boolean local = hostAddress.isSiteLocalAddress() || hostAddress.isLoopbackAddress();
        //assert local == yacyURL.isLocalDomain(url.hash()); // TODO: remove the dnsResolve above!
        final InetAddress ia = Domains.dnsResolve(host);
        return (local) ?
            ("the host '" + host + "' is local, but local addresses are not accepted: " + ((ia == null) ? "null" : ia.getHostAddress())) :
            ("the host '" + host + "' is global, but global addresses are not accepted: " + ((ia == null) ? "null" : ia.getHostAddress()));
    }

    public String urlInAcceptedDomainHash(final byte[] urlhash) {
        // returns true if the url can be accepted according to network.unit.domain
        if (urlhash == null) return "url is null";
        // check if this is a local address and we are allowed to index local pages:
        final boolean local = DigestURI.isLocal(urlhash);
        if (this.acceptLocalURLs && local) return null;
        if (this.acceptGlobalURLs && !local) return null;
        return (local) ?
            ("the urlhash '" + ASCII.String(urlhash) + "' is local, but local addresses are not accepted") :
            ("the urlhash '" + ASCII.String(urlhash) + "' is global, but global addresses are not accepted");
    }

    public boolean acceptLocalURLs() {
        return this.acceptLocalURLs;
    }

    public boolean acceptGlobalURLs() {
        return this.acceptGlobalURLs;
    }
}
