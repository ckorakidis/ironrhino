package org.ironrhino.security.oauth.server.component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.servlet.AccessHandler;
import org.ironrhino.core.servlet.HttpErrorHandler;
import org.ironrhino.core.session.HttpSessionManager;
import org.ironrhino.core.util.RequestUtils;
import org.ironrhino.core.util.UserAgent;
import org.ironrhino.security.oauth.server.model.Authorization;
import org.ironrhino.security.oauth.server.model.Client;
import org.ironrhino.security.oauth.server.service.OAuthManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OAuthHandler extends AccessHandler {

	public static final String REQUEST_ATTRIBUTE_KEY_OAUTH_REQUEST = "_OAUTH_REQUEST";
	public static final String REQUEST_ATTRIBUTE_KEY_OAUTH_CLIENT = "_OAUTH_CLIENT";
	public static final String SESSION_ID_PREFIX = "tk_";

	private Logger logger = LoggerFactory.getLogger(getClass());

	@Value("${oauth.api.pattern:/user/self,/oauth2/tokeninfo,/oauth2/revoketoken,/api/*}")
	private String apiPattern;

	@Value("${oauth.api.excludePattern:}")
	private String apiExcludePattern;

	@Autowired
	private OAuthManager oauthManager;

	@Autowired
	private UserDetailsService userDetailsService;

	@Autowired(required = false)
	private HttpErrorHandler httpErrorHandler;

	@Override
	public String getPattern() {
		return apiPattern;
	}

	public String getExcludePattern() {
		return apiExcludePattern;
	}

	@Override
	public boolean handle(HttpServletRequest request,
			HttpServletResponse response) {
		String errorMessage = null;
		Map<String, String> map = RequestUtils
				.parseParametersFromQueryString(request.getQueryString());
		String token = map.get("oauth_token");
		if (token == null)
			token = map.get("access_token");
		if (token == null) {
			String header = request.getHeader("Authorization");
			if (header != null) {
				header = header.trim();
				if (header.toLowerCase().startsWith("bearer ")) {
					// oauth 2.0
					token = header.substring("bearer ".length());
				} else if (header.toLowerCase().startsWith("oauth ")) {
					header = header.substring("oauth ".length());
					int i = header.indexOf("oauth_token=");
					if (i < 0) {
						// oauth 2.0
						token = header;
					} else {
						// oauth 1.0
						header = header.substring(header.indexOf("\"", i) + 1);
						token = header.substring(0, header.indexOf("\""));
					}
				} else {
					errorMessage = "invalid Authorization header,must starts with OAuth or Bearer";
				}
			}
		}
		if (StringUtils.isNotBlank(token)) {
			Authorization authorization = oauthManager.retrieve(token);
			if (authorization != null) {
				if (authorization.getExpiresIn() > 0) {
					String[] scopes = null;
					if (StringUtils.isNotBlank(authorization.getScope()))
						scopes = authorization.getScope().split("\\s");
					boolean authorized = (scopes == null);
					if (!authorized && scopes != null) {
						for (String s : scopes) {
							String requestURL = request.getRequestURL()
									.toString();
							if (requestURL.startsWith(s)) {
								authorized = true;
								break;
							}
						}
					}
					if (authorized) {
						Client client = oauthManager
								.findClientById(authorization.getClient());
						if (client == null || client.isEnabled()) {
							UserDetails ud = null;
							if (authorization.getGrantor() != null) {
								try {
									ud = userDetailsService
											.loadUserByUsername(authorization
													.getGrantor());
								} catch (UsernameNotFoundException unf) {
									logger.error(unf.getMessage(), unf);
								}

							} else if (client != null) {
								try {
									ud = userDetailsService
											.loadUserByUsername(client
													.getOwner().getUsername());
								} catch (UsernameNotFoundException unf) {
									logger.error(unf.getMessage(), unf);
								}
							}
							if (ud != null && ud.isEnabled()
									&& ud.isAccountNonExpired()
									&& ud.isAccountNonLocked()) {
								SecurityContext sc = SecurityContextHolder
										.getContext();
								Authentication auth = new UsernamePasswordAuthenticationToken(
										ud, ud.getPassword(),
										ud.getAuthorities());
								sc.setAuthentication(auth);
								Map<String, Object> sessionMap = new HashMap<String, Object>(
										2, 1);
								sessionMap
										.put(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
												sc);
								request.setAttribute(
										HttpSessionManager.REQUEST_ATTRIBUTE_KEY_SESSION_MAP_FOR_API,
										sessionMap);
								request.setAttribute(
										REQUEST_ATTRIBUTE_KEY_OAUTH_REQUEST,
										true);
								request.setAttribute(
										HttpSessionManager.REQUEST_ATTRIBUTE_KEY_SESSION_ID_FOR_API,
										SESSION_ID_PREFIX + token);
							}
							if (client != null) {
								request.setAttribute(
										REQUEST_ATTRIBUTE_KEY_OAUTH_CLIENT,
										client);
								UserAgent ua = new UserAgent(
										request.getHeader("User-Agent"));
								ua.setAppId(client.getId());
								ua.setAppName(client.getName());
								request.setAttribute("userAgent", ua);
							}
							return false;
						} else {
							errorMessage = "client_disabled";
						}
					} else {
						errorMessage = "unauthorized_scope";
					}
				} else {
					errorMessage = "expired_token";
				}
			} else {
				errorMessage = "invalid_token";
			}
		} else {
			errorMessage = "missing_token";
		}
		if (httpErrorHandler != null
				&& httpErrorHandler.handle(request, response,
						HttpServletResponse.SC_UNAUTHORIZED, errorMessage))
			return true;
		try {
			if (errorMessage != null)
				response.getWriter().write(errorMessage);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
					errorMessage);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}
		return true;
	}
}
