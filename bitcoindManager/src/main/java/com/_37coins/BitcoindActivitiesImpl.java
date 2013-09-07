package com._37coins;

import java.util.Map;


import com._37coins.activities.BitcoindActivities;
import com._37coins.bcJsonRpc.BitcoindInterface;
import com.google.inject.Inject;

public class BitcoindActivitiesImpl implements BitcoindActivities {

	@Inject
	BitcoindInterface client;

	@Override
	public Map<String,Object> sendTransaction(Map<String,Object> rsp){
		if (null!=rsp.get("receiverAccount")){
			String rv = client.move((String)rsp.get("account"), (String)rsp.get("receiverAccount"), (double)rsp.get("amount"));
			rsp.put("txHash", rv);
		}else{
			String rv = client.sendfrom((String)rsp.get("account"), (String)rsp.get("receiver"), (double)rsp.get("amount"));
			rsp.put("txHash", rv);
		}
		return rsp;
	}

	@Override
	public Map<String, Object> getAccountBalance(Map<String, Object> rsp) {
		double rv = client.getbalance((String)rsp.get("account"), 0);
		rsp.put("balance", rv);
		if (((String)rsp.get("action")).equalsIgnoreCase("confirmSend")
				|| ((String)rsp.get("action")).equalsIgnoreCase("send")){
			rsp.put("fee", BitcoindServletConfig.fee);
			rsp.put("feeAddress", BitcoindServletConfig.feeAddress);
		}
		return rsp;
	}

	@Override
	public Map<String, Object> createBcAccount(Map<String, Object> rsp) {
		String address = client.getaccountaddress((String)rsp.get("account"));
		rsp.put("bcAddress", address);
		return rsp;
	}

	@Override
	public Map<String, Object> getNewAddress(Map<String, Object> rsp) {
		String address = client.getaccountaddress((String)rsp.get("account"));
		rsp.put("bcAddress", address);
		return rsp;
	}

	@Override
	public Map<String, Object> getAccount(Map<String, Object> rsp) {
		String rv = client.getaccount((String)rsp.get("receiver"));
		if (rv!=null && rv.length()>0){
			rsp.put("receiverAccount", rv);
		}
		return rsp;
	}

}