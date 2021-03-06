/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.mdolidon.hamster.configuration;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mdolidon.hamster.core.IConfiguration;
import org.mdolidon.hamster.core.IMatcher;
import org.mdolidon.hamster.core.Link;
import org.mdolidon.hamster.core.MatcherDrivenList;
import org.mdolidon.hamster.core.TargetProfile;
import org.mdolidon.hamster.matchers.All;

/**
 * This is a standalone implementation of IConfiguration, that embeds all the
 * necessary logic to answer queries, but lets a caller or a subclass define the
 * rules.
 */
public class BaseConfiguration implements IConfiguration {

	private static Logger logger = LogManager.getLogger();

	private URL startUrl = null;
	private int maxConcurrentRequests = 10;

	private MatcherDrivenList<IStorageResolver> storageRules = new MatcherDrivenList<>();
	private MatcherDrivenList<IDownloadRule> downloadRules = new MatcherDrivenList<>();
	private MatcherDrivenList<IAuthenticationRule> authenticationRules = new MatcherDrivenList<>();
	private MatcherDrivenList<IContentSizeRule> contentSizeRules = new MatcherDrivenList<>();

	private List<IMatcher> authenticationRulesMatchers = new ArrayList<>();
	private List<CheckinDirective> checkInDirectives = new ArrayList<>();
	private List<CookiesDirective> cookiesDirectives = new ArrayList<>();

	public BaseConfiguration() throws Exception {
		storageRules.setDefault(new FlatStorageResolver(new All(), this, null));
		downloadRules.setDefault(new AvoidRule(new All()));
		authenticationRules.setDefault(new AnonymousAuthenticationRule(new All()));
		contentSizeRules.setDefault(new ConstantContentSizeRule(5242880, new All()));
	}

	@Override
	public boolean isValid() {
		return listErrors().size() == 0;
	}

	public List<String> listErrors() {
		List<String> errors = new ArrayList<>(2);
		if (startUrl == null) {
			errors.add("No valid start URL was defined.");
		}
		if (maxConcurrentRequests <= 0) {
			errors.add("Invalid number of parallel downloads.");
		}
		return errors;
	}

	@Override
	public String getErrorMessage() {
		return String.join("\n", listErrors());
	}

	@Override
	public URL getStartUrl() {
		return startUrl;
	}

	@Override
	public String getStartUrlAsString() {
		if (startUrl != null) {
			return startUrl.toString();
		} else {
			return null;
		}
	}

	public void setStartUrl(URL startUrl) {
		this.startUrl = startUrl;
	}

	@Override
	public void correctStartUrl(URL url) {
		setStartUrl(url);
		logger.info("Start URL corrected to {}", url);
	}

	@Override
	public int getMaxConcurrentRequests() {
		return maxConcurrentRequests;
	}

	public void setMaxConcurrentRequests(int maxConcurrentRequests) {
		this.maxConcurrentRequests = maxConcurrentRequests;
	}

	public void addStorageResolver(IStorageResolver resolver) {
		storageRules.add(resolver);
	}

	public void addDownloadRule(IDownloadRule rule) {
		downloadRules.add(rule);
	}

	public void addCheckin(CheckinDirective directive) {
		checkInDirectives.add(directive);
	}

	@Override
	public List<HttpPost> getCheckinPostRequests() {
		List<HttpPost> requests = new ArrayList<>();
		for (CheckinDirective directive : checkInDirectives) {
			HttpPost request = directive.getPostRequest();
			if (request != null) {
				requests.add(request);
			}
		}
		return requests;
	}

	@Override
	public TargetProfile getTargetProfile(Link link) {
		if (link == null) {
			throw new NullPointerException();
		}

		if (link.getJumpsFromStartingURL() == 0) {
			logger.debug("Identified start URL. It will be included in the target+download set.");
			return new TargetProfile(true, true);
		}

		IDownloadRule rule = downloadRules.getFirstMatch(link);
		TargetProfile profile = rule.getTargetProfile(link);

		return profile;
	}

	// we need to store the list of matchers in the config, hence we pass it here
	// besides
	public void addAuthenticationRule(IAuthenticationRule rule, IMatcher matcher) {
		authenticationRules.add(rule);
		authenticationRulesMatchers.add(matcher);
	}

	public List<IMatcher> listAuthContextMatchers() {
		return authenticationRulesMatchers;
	}

	public HttpClientContext makeHttpContext(Link link) {
		HttpClientContext context = HttpClientContext.create();
		context.setCookieStore(new BasicCookieStore());

		IAuthenticationRule rule = authenticationRules.getFirstMatch(link);
		rule.applyToHttpContext(context);
		for (CookiesDirective ckd : cookiesDirectives) {
			ckd.addCookiesToClientContext(context);
		}
		return context;
	}

	public void addCookiesDirective(CookiesDirective cookiesDirective) {
		cookiesDirectives.add(cookiesDirective);
	}
	
	// This is a testing hook
	public CookiesDirective getFirstCookieDirective() {
		return cookiesDirectives.get(0);
	}

	public void addContentSizeRule(IContentSizeRule rule) {
		contentSizeRules.add(rule);
	}

	@Override
	public boolean isAcceptableContentSize(Link link, long size) {
		return contentSizeRules.getFirstMatch(link).isAcceptableContentSize(link, size);
	}

	// Guarantees to return a relative path
	// Mutates link property
	@Override
	public File getStorageFile(Link link) {
		if (link == null) {
			throw new NullPointerException();
		}

		IStorageResolver resolver = storageRules.getFirstMatch(link);
		File file = resolver.getStorageFile(link);

		// Verify the resolver's work
		if (file.isAbsolute()) {
			logger.error(
					"You must specify storage locations in relative paths. Got absolute path for {} -- This preferred location will be ignored.",
					file);
		}
		return file;
	}
}
