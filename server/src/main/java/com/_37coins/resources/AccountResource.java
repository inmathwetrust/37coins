package com._37coins.resources;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;
import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

import org.apache.commons.lang3.RandomStringUtils;
import org.restnucleus.dao.GenericRepository;
import org.restnucleus.dao.RNQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com._37coins.MessageFactory;
import com._37coins.MessagingServletConfig;
import com._37coins.parse.ParserAction;
import com._37coins.parse.ParserClient;
import com._37coins.persistence.dao.Account;
import com._37coins.persistence.dao.Gateway;
import com._37coins.sendMail.MailServiceClient;
import com._37coins.web.AccountPolicy;
import com._37coins.web.AccountRequest;
import com._37coins.web.GatewayUser;
import com._37coins.web.PasswordRequest;
import com._37coins.workflow.NonTxWorkflowClientExternalFactoryImpl;
import com._37coins.workflow.pojo.DataSet;
import com._37coins.workflow.pojo.DataSet.Action;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import freemarker.template.TemplateException;

@Path(AccountResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource {
	public final static String PATH = "/account";
	public static Logger log = LoggerFactory.getLogger(AccountResource.class);
	
	private final Cache cache;
	private final NonTxWorkflowClientExternalFactoryImpl nonTxFactory;
	private final HttpServletRequest httpReq;
	private final AccountPolicy accountPolicy;
	private final ParserClient parserClient;
	private final MailServiceClient mailClient;
	private final MessageFactory msgFactory;
	private final GenericRepository dao;
	private int localPort;

	@Inject
	public AccountResource(Cache cache,
			ServletRequest request,
			AccountPolicy accountPolicy,
			MailServiceClient mailClient,
			MessageFactory msgFactory,
			NonTxWorkflowClientExternalFactoryImpl nonTxFactory,
			ParserClient parserClient){
		this.cache = cache;
		httpReq = (HttpServletRequest)request;
		dao = (GenericRepository)httpReq.getAttribute("gr");
		this.accountPolicy = accountPolicy;
		this.mailClient = mailClient;
		this.msgFactory = msgFactory;
		this.parserClient = parserClient;
		this.nonTxFactory = nonTxFactory;
		localPort = httpReq.getLocalPort();
	}
	
	/**
	 * allow front-end to notify user about taken account 
	 * @param email
	 */
	@GET
	@Path("/check")
	public String checkEmail(@QueryParam("email") String email){
	    //check it's a valid email
        if (!AccountPolicy.isValidEmail(email)){
            return "false";
        }
        //how to avoid account phishing?
        Element e = cache.get(IndexResource.getRemoteAddress(httpReq));
        if (e!=null){
            if (e.getHitCount()>50){
                throw new WebApplicationException("to many requests", Response.Status.FORBIDDEN);
            }
        }
        //check it's not taken already
        Gateway g = dao.queryEntity(new RNQuery().addFilter("email", email), Gateway.class, false);
        if (null!=g){
            return "false";//ldap error
        }
		return "true";
	}
	
	@GET
	@Path("/find")
	public String findByEmail(@QueryParam("mobile") String mobile){
		//how to avoid account fishing?
		Element e = cache.get(TicketResource.REQUEST_SCOPE+TicketResource.getRemoteAddress(httpReq));
		if (e!=null){
			if (e.getHitCount()>50){
				throw new WebApplicationException("to many requests", Response.Status.FORBIDDEN);
			}
		}
		//check it's not taken already
		try{
    		PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
    		PhoneNumber pn = phoneUtil.parse(mobile, "ZZ");
    		mobile = phoneUtil.format(pn, PhoneNumberFormat.E164);
    		//check if it's not an account already
    		Gateway g = dao.queryEntity(new RNQuery().addFilter("mobile", mobile), Gateway.class, false);
    		if (g==null){
    		    throw new WebApplicationException("no gateway found",javax.ws.rs.core.Response.Status.NOT_FOUND);
    		}
    		return g.getCn();
		}catch(Exception ex){
			throw new WebApplicationException(ex,javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR);
		}
	}
	
	@POST
	@Path("/invite")
	public Map<String, String> invite(GatewayUser gu){
		final DataSet ds = new DataSet();
		PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
		String mobile = null;
		try{
			PhoneNumber pn = phoneUtil.parse(gu.getMobile(), "ZZ");
			mobile = phoneUtil.format(pn, PhoneNumberFormat.E164);
			//check if it's not an account already
	        Account g = dao.queryEntity(new RNQuery().addFilter("mobile", mobile), Account.class, false);
            if (g!=null){
                throw new WebApplicationException("exists already.", Response.Status.CONFLICT);
            }
			parserClient.start(mobile, null, Action.SIGNUP.toString(), localPort,
			new ParserAction() {
				@Override
				public void handleResponse(DataSet data) {
					if (null!=data && data.getAction()==Action.SIGNUP){
						nonTxFactory.getClient(data.getAction()+"-"+data.getCn()).executeCommand(data);
					}
					ds.setAction(data.getAction());
					ds.setCn(data.getCn());
					ds.setTo(data.getTo());
				}
				@Override
				public void handleDeposit(DataSet data) {}
				@Override
				public void handleConfirm(DataSet data) {}
				@Override
				public void handleWithdrawal(DataSet data) {}
			});
		}catch(NumberParseException e){
			throw new WebApplicationException("number format issue",
					javax.ws.rs.core.Response.Status.BAD_REQUEST);
		}
		try {
			parserClient.join(1500L);
		} catch (InterruptedException e2) {
			throw new WebApplicationException("could not join parser thread",
					javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR);
		}
		if (null!=ds && ds.getAction()==Action.SIGNUP){
			//the web frontend will call the webfinger resource after this
			//make sure it will only search in the cache
			cache.put(new Element("addressReq"+mobile.replace("+", ""), true));
			Map<String,String> rv = new HashMap<>();
			rv.put("cn",ds.getCn());
			return rv;
		}else if (null!=ds && ds.getAction()==Action.DST_ERROR){
			throw new WebApplicationException("no gateway found",
					javax.ws.rs.core.Response.Status.NOT_FOUND);
		}
		throw new WebApplicationException("unknown", javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR);
	}

	/**
	 * an account-request is validated, and cached, then email is send
	 * @param accountRequest
	 */
	@POST
	public void register(AccountRequest accountRequest){
		// no ticket, no service
		if (null==cache.get(TicketResource.TICKET_SCOPE+accountRequest.getTicket())){
			log.debug("ticket required for this operation.");
			throw new WebApplicationException("ticket required for this operation.", Response.Status.EXPECTATION_FAILED);
		}
		//#############validate email#################
		//check regex
		if (null==accountRequest.getEmail() || !AccountPolicy.isValidEmail(accountRequest.getEmail())){
			log.debug("send a valid email plz :D");
			throw new WebApplicationException("send a valid email plz :D", Response.Status.BAD_REQUEST);
		}
		//check it's not taken already
        Gateway g = dao.queryEntity(new RNQuery().addFilter("email", accountRequest.getEmail()), Gateway.class, false);
        if (g!=null){
            throw new WebApplicationException("exists already.", Response.Status.CONFLICT);
        }
        if (accountPolicy.isEmailMxLookup()){
            //check db for active email with same domain
            RNQuery q = new RNQuery()
                .addFilter("hostName",  getHostName(accountRequest.getEmail()));
            List<Gateway> accounts = dao.queryList(q, Gateway.class);
            if (accounts==null || accounts.size() == 0){
                //check host mx record
                boolean isValidMX = false;
                try{
                    isValidMX = AccountPolicy.isValidMX(accountRequest.getEmail());
                }catch(Exception e){
                    log.error("EmailRes.->check: "+ accountRequest.getEmail() + " not valid due: " + e.getMessage());
                }
                if(!isValidMX ){
                    throw new WebApplicationException("This email's hostname does not have mx record.", Response.Status.EXPECTATION_FAILED);
                }
            }
        }
		//################validate password############
		boolean isValid = accountPolicy.validatePassword(accountRequest.getPassword());
		if (!isValid){
			log.debug("password does not pass account policy");
			throw new WebApplicationException("password does not pass account policy", Response.Status.BAD_REQUEST);
		}
		//put it into cache, and wait for email validation
		String token = RandomStringUtils.random(14, "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ123456789");
		try {
			sendCreateEmail(accountRequest.getEmail() ,token);
		} catch (MessagingException | IOException| TemplateException e1) {
			log.error("register exception", e1);
			e1.printStackTrace();
			throw new WebApplicationException(e1,Response.Status.INTERNAL_SERVER_ERROR);
		}
		AccountRequest ar = new AccountRequest()
			.setEmail(accountRequest.getEmail())
			.setPassword(accountRequest.getPassword());
		cache.put(new Element("create"+token,ar));
	}
	
	   public static String getHostName(String email){
	        String hostName = email.substring(email.indexOf("@") + 1, email.length());
	        return hostName;
	    }
	
	/**
	 * an account-request is taken from cache and put into the database
	 * @param accountRequest
	 */
	@POST
	@Path("/create")
	public void createAccount(AccountRequest accountRequest){
		Element e = cache.get("create"+accountRequest.getToken());
		if (null!=e){
			accountRequest = (AccountRequest)e.getObjectValue();
			if (checkEmail(accountRequest.getEmail()).equals("false")){
				throw new WebApplicationException("account created already", Response.Status.BAD_REQUEST);
			}
	         String cnString = RandomStringUtils.random(16, "ABCDEFGHJKLMNPQRSTUVWXYZ123456789");
			Gateway a = new Gateway()
                .setEmail(accountRequest.getEmail())
                .setCn(cnString)
                .setPassword(accountRequest.getPassword());
            dao.add(a);
			cache.remove("create"+accountRequest.getToken());
		}else{
			throw new WebApplicationException("not found or expired", Response.Status.NOT_FOUND);
		}
	}
	
	/**
	 * a password-request is validated, cached and email send out
	 * @param pwRequest
	 */
	@POST
	@Path("/password/request")
    public void requestPwReset(PasswordRequest pwRequest){
        // no ticket, no service
        Element e = cache.get("ticket"+pwRequest.getTicket());
        if (null==e){
            throw new WebApplicationException("ticket required for this request.", Response.Status.BAD_REQUEST);
        }else{
            if (e.getHitCount()>3){
                cache.remove("ticket"+pwRequest.getTicket());
                throw new WebApplicationException("to many requests", Response.Status.BAD_REQUEST);
            }
        }
        //fetch account by email, then send email
        Account a = dao.queryEntity(new RNQuery().addFilter("email", pwRequest.getEmail()), Account.class,false);
        if (a == null)
            throw new WebApplicationException("account not found", Response.Status.NOT_FOUND);
        String token = RandomStringUtils.random(14, "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ123456789");
        try{
            sendResetEmail(pwRequest.getEmail(), token);
        } catch (MessagingException | IOException| TemplateException e1) {
            e1.printStackTrace();
            throw new WebApplicationException(e1,Response.Status.INTERNAL_SERVER_ERROR);
        }
        PasswordRequest pwr = new PasswordRequest().setToken(token).setAccountId(a.getId());
        cache.put(new Element("reset"+token, pwr));
    }
	
    /**
     * a password-request is taken from the cache and executed, then account-changes persisted
     * @param pwRequest
     */
    @POST
    @Path("/password/reset")
    public void reset(PasswordRequest pwRequest){
        Element e = cache.get("reset"+pwRequest.getToken());
        if (null!=e){
            String newPw = pwRequest.getPassword();
            boolean isValid = accountPolicy.validatePassword(newPw);
            if (!isValid)
                throw new WebApplicationException("password does not pass account policy", Response.Status.BAD_REQUEST);
            pwRequest = (PasswordRequest)e.getObjectValue();
            Gateway a = dao.getObjectById(pwRequest.getAccountId(), Gateway.class);
            a.setPassword(newPw);
            cache.remove("reset"+pwRequest.getToken());
        }else{
            throw new WebApplicationException("not found or expired", Response.Status.NOT_FOUND);
        }
    }
	
	private void sendResetEmail(String email, String token) throws AddressException, MessagingException, IOException, TemplateException{
		DataSet ds = new DataSet()
			.setLocale(Locale.ENGLISH)
			.setAction(Action.RESET)
			.setPayload(MessagingServletConfig.basePath+"#confReset/"+token);
		mailClient.send(
			msgFactory.constructSubject(ds), 
			email,
			MessagingServletConfig.senderMail, 
			msgFactory.constructTxt(ds),
			msgFactory.constructHtml(ds));
	}
	
	private void sendCreateEmail(String email, String token) throws AddressException, MessagingException, IOException, TemplateException{
		DataSet ds = new DataSet()
			.setLocale(Locale.ENGLISH)
			.setAction(Action.REGISTER)
			.setPayload(MessagingServletConfig.basePath+"#confSignup/"+token);
		mailClient.send(
			msgFactory.constructSubject(ds), 
			email,
			MessagingServletConfig.senderMail, 
			msgFactory.constructTxt(ds),
			msgFactory.constructHtml(ds));
	}
	
}
