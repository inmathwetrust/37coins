package com._37coins;

import java.io.IOException;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.event.MessageCountEvent;
import javax.mail.event.MessageCountListener;
import javax.mail.internet.InternetAddress;

import com._37coins.parse.MessageParser;
import com._37coins.parse.RequestInterpreter;
import com._37coins.sendMail.MailTransporter;
import com._37coins.workflow.NonTxWorkflowClientExternalFactoryImpl;
import com._37coins.workflow.WithdrawalWorkflowClientExternalFactoryImpl;
import com._37coins.workflow.pojo.MessageAddress;
import com._37coins.workflow.pojo.MessageAddress.MsgType;
import com._37coins.workflow.pojo.Request;
import com._37coins.workflow.pojo.Response;
import com.amazonaws.services.simpleworkflow.AmazonSimpleWorkflow;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import freemarker.template.TemplateException;

public class EmailListener implements MessageCountListener{
	
	@Inject 
	WithdrawalWorkflowClientExternalFactoryImpl withdrawalFactory;
	
	@Inject 
	NonTxWorkflowClientExternalFactoryImpl nonTxFactory;
	
	@Inject
	MailTransporter mt;
	
	@Inject
	MessageParser mp;
	
	@Inject @Named("wfClient")
	AmazonSimpleWorkflow swfService;

	@Override
	public void messagesRemoved(MessageCountEvent e) {
	}

	@Override
	public void messagesAdded(MessageCountEvent e) {
		
		for (Message m : e.getMessages()) {
			try{
			//parse from
			String from = null;
			if (null == m.getFrom() || m.getFrom().length != 1) {
				Response rsp = new Response();
				mt.sendMessage(rsp);
				return;
			} else {
				from = ((InternetAddress) m.getFrom()[0]).getAddress();
			}
			MessageAddress md = new MessageAddress()
			.setAddress(from)
			.setAddressType(MsgType.EMAIL)
			.setGateway(MessagingServletConfig.imapUser+"@"+MessagingServletConfig.imapHost);
			
			//implement actions
			RequestInterpreter ri = new RequestInterpreter(mp,swfService) {							
				@Override
				public void startWithdrawal(Request req, String workflowId) {
					withdrawalFactory.getClient(workflowId).executeCommand(req);
				}
				@Override
				public void startDeposit(Request req) {
					nonTxFactory.getClient().executeCommand(req);
				}
				@Override
				public void respond(Response rsp) {
					try {
						mt.sendMessage(rsp);
					} catch (IOException | TemplateException
							| MessagingException e) {
						e.printStackTrace();
					}
				}
			};

			//interprete received message/command
			ri.process(md, m.getSubject());
			}catch(Exception ex){
				ex.printStackTrace();
			}
		}
	}

}