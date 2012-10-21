package nl.limesco.cserv.cdr.api;

import java.util.Calendar;
import java.util.Map;

public interface Cdr {
	
	public enum Type {
		EXT_EXT     ,  BPX_EXT     ,  MOBILE_EXT     ,
		EXT_PBX     ,  BPX_BPX     ,  MOBILE_BPX     ,
		EXT_MOBILE  ,  BPX_MOBILE  ,  MOBILE_MOBILE  ,
		UNKNOWN
	}

	String getSource();
	
	String getCallId();
	
	String getAccount();
	
	Calendar getTime();
	
	String getFrom();
	
	String getTo();
	
	boolean isConnected();
	
	Type getType();
	
	long getSeconds();
	
	Map<String, String> getAdditionalInfo();
	
}