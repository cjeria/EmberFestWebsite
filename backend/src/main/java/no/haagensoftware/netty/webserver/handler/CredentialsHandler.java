package no.haagensoftware.netty.webserver.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import no.haagensoftware.auth.MozillaPersonaCredentials;
import no.haagensoftware.leveldb.LevelDbEnv;
import no.haagensoftware.netty.webserver.AuthenticationContext;
import no.haagensoftware.netty.webserver.AuthenticationResult;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.log4j.Logger;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class CredentialsHandler extends FileServerHandler {
	private static Logger logger = Logger.getLogger(CredentialsHandler.class.getName());
	
	private AuthenticationContext authenticationContext;
	private LevelDbEnv dbEnv;
	
	
	public CredentialsHandler(String path, AuthenticationContext authenticationContext, LevelDbEnv dbEnv) {
		super(path);
		this.authenticationContext = authenticationContext;
		this.dbEnv = dbEnv;
	}
	
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
			throws Exception {
		
		String uri = getUri(e);
		String cookieUuidToken = getCookieValue(e, "uuidToken");
		AuthenticationResult cachedUserResult = null;
		if (cookieUuidToken != null) {
			cachedUserResult = authenticationContext.verifyUUidToken(cookieUuidToken);
		}
		String responseContent = "";
		
		if (isPost(e) && uri != null && uri.endsWith("login")) {
			String messageContent = getHttpMessageContent(e);
			
			if (cachedUserResult != null && cachedUserResult.getUuidToken() != null && cachedUserResult.isUuidValidated()) {
				logger.info("User Cached. Logging in automatically without Persona.");
				responseContent = "{ \"uuidToken\": \"" + cachedUserResult.getUuidToken() + "\", \"authLevel\": \"" + authenticationContext.getUserAuthLevel(cachedUserResult.getUuidToken()) + "\"}";
			} else {
				logger.info("Logging in via Persona.");
				
				responseContent = loginViaMozillaPersona(responseContent, messageContent);
		        
		        MozillaPersonaCredentials credentials = new Gson().fromJson(responseContent, MozillaPersonaCredentials.class);
		        AuthenticationResult authResult = authenticationContext.verifyAndGetUser(credentials);
		        if (authResult.getUuidToken() != null && authResult.isUuidValidated()) {
		        	responseContent = "{ \"uuidToken\": \"" + authResult.getUuidToken() + "\", \"authLevel\": \"" + authenticationContext.getUserAuthLevel(authResult.getUuidToken()) + "\"}";
		        } else if (authResult.getUuidToken() != null && !authResult.isUuidValidated()) {
		        	responseContent = "{ \"uuidToken\": \"" + authResult.getUuidToken() + "\", \"authLevel\": \"" + authenticationContext.getUserAuthLevel(authResult.getUuidToken()) + "\"}";
		        } else {
		        	responseContent = "{ \"authFailed\": true, \"error\": \"" + authResult.getStatusMessage() + "\" }";
		        }
			}
			
	        logger.info(responseContent);
	        
	        writeContentsToBuffer(ctx, responseContent, "text/json");
		} else if (isPost(e) && uri != null && uri.endsWith("logout") && cookieUuidToken != null) {
			authenticationContext.logUserOut(cookieUuidToken);
			responseContent = "{\"loggedOut\": true}";
		}
		
		logger.info("responseContent: " + responseContent);
		logger.info("coookieUuidToken " + cookieUuidToken);
		writeContentsToBuffer(ctx, responseContent, "text/json");
	}

	private String loginViaMozillaPersona(String responseContent,
			String messageContent) throws UnsupportedEncodingException,
			IOException, ClientProtocolException {
		
		JsonObject assertionJson = new JsonObject();
		if (messageContent.startsWith("assertion=")) {
			messageContent = messageContent.substring(10, messageContent.length());
		}
		assertionJson.addProperty("assertion", messageContent);
		assertionJson.addProperty("audience", "http://localhost:8081");
		
		int statusCode = -1;
		DefaultHttpClient httpclient = new DefaultHttpClient();
		
		HttpPost httpPost = new HttpPost("https://verifier.login.persona.org/verify");
		System.out.println(assertionJson.toString());
		StringEntity requestEntity = new StringEntity(assertionJson.toString(), "UTF-8");
		//requestEntity.setContentType("application/x-www-form-urlencoded");
		requestEntity.setContentType("application/json");

		httpPost.setEntity(requestEntity);
		HttpResponse response = httpclient.execute(httpPost);
		statusCode = response.getStatusLine().getStatusCode();

		BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
		
		String line = "";
		while ((line = rd.readLine()) != null) {
		  responseContent = line + "\n";
		}
		return responseContent;
	}	
}
