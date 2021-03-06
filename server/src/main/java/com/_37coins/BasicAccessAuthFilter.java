package com._37coins;

import javax.inject.Inject;
import javax.naming.LimitExceededException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.realm.ldap.JndiLdapContextFactory;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicAccessAuthFilter extends BasicHttpAuthenticationFilter {
	public static final String SF = "(&(objectClass=person)(|(mail={0})(cn={0})(givenName={0})))";
	private static final Logger log = LoggerFactory.getLogger(BasicAccessAuthFilter.class);
	
	final private JndiLdapContextFactory jlc;
	
	@Inject
	public BasicAccessAuthFilter(JndiLdapContextFactory jlc) {
		this.setApplicationName("Password Self Service");
		this.setAuthcScheme("B4S1C");
		this.jlc = jlc;
	}
	
	@Override
	protected boolean isAccessAllowed(ServletRequest request,
			ServletResponse response, Object mappedValue) {
		HttpServletRequest httpRequest = WebUtils.toHttp(request);
		String httpMethod = httpRequest.getMethod();
		if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
			return true;
		} else {
			return super.isAccessAllowed(request, response, mappedValue);
		}
	}
	
	//taken from https://www.owasp.org/index.php/Preventing_LDAP_Injection_in_Java
	public static final String escapeLDAPSearchFilter(String filter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filter.length(); i++) {
            char curChar = filter.charAt(i);
            switch (curChar) {
                case '\\':
                    sb.append("\\5c");
                    break;
                case '*':
                    sb.append("\\2a");
                    break;
                case '(':
                    sb.append("\\28");
                    break;
                case ')':
                    sb.append("\\29");
                    break;
                case '\u0000': 
                    sb.append("\\00"); 
                    break;
                default:
                    sb.append(curChar);
            }
        }
        return sb.toString();
    }
	
	//taken from  https://www.owasp.org/index.php/Preventing_LDAP_Injection_in_Java
    public static String escapeDN(String name) {
        StringBuilder sb = new StringBuilder(); 
        if ((name.length() > 0) && ((name.charAt(0) == ' ') || (name.charAt(0) == '#'))) {
            sb.append('\\'); // add the leading backslash if needed
        }
        for (int i = 0; i < name.length(); i++) {
            char curChar = name.charAt(i);
            switch (curChar) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case ',':
                    sb.append("\\,");
                    break;
                case '+':
                    sb.append("\\+");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '<':
                    sb.append("\\<");
                    break;
                case '>':
                    sb.append("\\>");
                    break;
                case ';':
                    sb.append("\\;");
                    break;
                default:
                    sb.append(curChar);
            }
        }
        if ((name.length() > 1) && (name.charAt(name.length() - 1) == ' ')) {
            sb.insert(sb.length() - 1, '\\'); // add the trailing backslash if needed
        }
        return sb.toString();
    }
	
	public static SearchResult searchUnique(String searchFilter,InitialLdapContext ctx) throws IllegalStateException, NamingException{
		ctx.setRequestControls(null);
		SearchControls searchControls = new SearchControls();
		searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		searchControls.setTimeLimit(500);
		NamingEnumeration<?> namingEnum = ctx.search(MessagingServletConfig.ldapBaseDn, searchFilter, searchControls);
		if (namingEnum.hasMore ()){
			SearchResult result = (SearchResult) namingEnum.next();
			if (namingEnum.hasMore()){
				throw new LimitExceededException("search with filter "+searchFilter+" returned more than 1 result");
			}
			namingEnum.close();
			return result;
		}else{
			throw new NameNotFoundException("search with filter "+searchFilter+" returned no result");
		}
	}
	
	@Override
	protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
        String authorizationHeader = getAuthzHeader(request);
        if (authorizationHeader == null || authorizationHeader.length() == 0) {
            // Create an empty authentication token since there is no
            // Authorization header.
            return createToken("", "", request, response);
        }

        if (log.isDebugEnabled()) {
            log.debug("Attempting to execute login with headers [" + authorizationHeader + "]");
        }

        String[] prinCred = getPrincipalsAndCredentials(authorizationHeader, request);
        if (prinCred == null || prinCred.length < 2) {
            // Create an authentication token with an empty password,
            // since one hasn't been provided in the request.
            String username = prinCred == null || prinCred.length == 0 ? "" : prinCred[0];
            return createToken(username, "", request, response);
        }
        
        String username = prinCred[0];
        String password = prinCred[1];
    	String sf = SF.replace("{0}", BasicAccessAuthFilter.escapeLDAPSearchFilter(username));
		try {
			AuthenticationToken at = new UsernamePasswordToken(MessagingServletConfig.ldapUser, MessagingServletConfig.ldapPw);
			InitialLdapContext ctx = (InitialLdapContext)jlc.getLdapContext(at.getPrincipal(),at.getCredentials());
	    	SearchResult result = BasicAccessAuthFilter.searchUnique(sf, ctx);
	        Attributes attrs = result.getAttributes();
	        String cn = BasicAccessAuthFilter.escapeDN((String)attrs.get("cn").get(0));
	        username = "cn="+cn+",ou=gateways,"+MessagingServletConfig.ldapBaseDn;
	        ctx.close();
		} catch (IllegalStateException | NamingException e) {
			log.error("auth filter directory miss",e);
			e.printStackTrace();
		}
        return createToken(username, password, request, response);
	}

}
