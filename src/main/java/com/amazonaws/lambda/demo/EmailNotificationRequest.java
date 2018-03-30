package com.amazonaws.lambda.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.Writer;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.TimeToLiveSpecification;
import com.amazonaws.services.dynamodbv2.model.UpdateTimeToLiveRequest;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.ParseException;
import org.json.simple.parser.JSONParser;

public class EmailNotificationRequest implements RequestStreamHandler {

	static AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().build();
    static DynamoDB dynamoDB = new DynamoDB(client);
	
	@Override
	public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {

		JSONParser parser = new JSONParser();
		LambdaLogger logger = context.getLogger();
		logger.log("Loading Java Lambda handler of ProxyWithStream");

		BufferedReader reader = new BufferedReader(new InputStreamReader(input));
		JSONObject responseJson = new JSONObject();
		String emailAddress = null;
		String responseCode = "200";

		try {
			JSONObject event = (JSONObject) parser.parse(reader);
			logger.log("Parsing incoming data..");
			logger.log(event.toJSONString() + "\n");
			if (event.get("body") != null) {
				JSONObject body = (JSONObject) parser.parse((String) event.get("body"));
				if (body.get("email") != null) {
					emailAddress = (String) body.get("email");
				}
			}
			logger.log("Email Address: " + emailAddress);
			String emailToken = checkToken(emailAddress, logger);
			if (emailAddress == null) {
				throw new NullPointerException("Couldn't parse the input email address");
			}
			if (emailToken == null) {
				emailToken = generateNewToken(emailAddress, logger);
				this.sendingResettingEmail(emailAddress, emailToken, logger);
			}
			
			JSONObject responseBody = new JSONObject();
			responseBody.put("message", "Email has been sent to the address " + emailAddress);
			JSONObject headerJson = new JSONObject();
			headerJson.put("x-custom-header", "my custom header value");
			responseJson.put("isBase64Encoded", false);
			responseJson.put("statusCode", responseCode);
			responseJson.put("headers", headerJson);
			responseJson.put("body", responseBody.toString());

		} catch (Exception e) {
			responseJson.put("statusCode", "400");
			responseJson.put("exception", e);
		}

		logger.log(responseJson.toJSONString());
		OutputStreamWriter writer = new OutputStreamWriter(output, "UTF-8");
		writer.write(responseJson.toJSONString());
		writer.close();
	}

	public void sendingResettingEmail(String emailAddress, String token, LambdaLogger logger) throws Exception {
		if (emailAddress == null || emailAddress.equals("")) {
			throw new IllegalArgumentException("Email Address is not set yet.");
		}
		String from = "noreply@csye6225-spring2018-wux.me";
		String to = emailAddress;
		String subject = "Resetting your password";
		String resetLink = "http://csye6225-spring2018-wux.me/reset?email=" + emailAddress + "&token=" + token;
		String htmlbody = "<h1>Password Resetting Service</h1>"
				+ "<p>This email was sent by letting you reset your password!" + "Please click link: " + resetLink + " to move forward!";
		String textbody = "this email was sent by letting you reset your password!";
		AmazonSimpleEmailService client = new AmazonSimpleEmailServiceClient();
		SendEmailRequest request = new SendEmailRequest().withDestination(new Destination().withToAddresses(to))
				.withMessage(new Message()
						.withBody(new Body().withHtml(new Content().withCharset("UTF-8").withData(htmlbody))
								.withText(new Content().withCharset("UTF-8").withData(textbody)))
						.withSubject(new Content().withCharset("UTF-8").withData(subject)))
				.withSource(from);
		client.sendEmail(request);
		logger.log("Email has been sent to " + emailAddress);
	}

//	public void setTTL() {
//		String tableName = "password_Reset";
//		final UpdateTimeToLiveRequest req = new UpdateTimeToLiveRequest();
//        req.setTableName(tableName);
//
//        final TimeToLiveSpecification ttlSpec = new TimeToLiveSpecification();
//        ttlSpec.setAttributeName("ttl");
//        ttlSpec.setEnabled(true);
//        req.withTimeToLiveSpecification(ttlSpec);
//
//        client.updateTimeToLive(req);
//	}
	
	@SuppressWarnings("unused")
	public String checkToken(String email, LambdaLogger logger) {
		String tableName = "password_Reset";
		
        Table table = dynamoDB.getTable(tableName);
        //try {
        Item item = table.getItem("email", email, "email, reset_token", null);
        if (item == null) {
        	return null;
        } else {
            logger.log(item.getString("reset_token"));
        }
        return item.getString("reset_token");
	}
	
	public String generateNewToken(String emailAddress, LambdaLogger logger) {
		String tableName = "password_Reset";
		Table table = dynamoDB.getTable(tableName);
        try {
        	String token = randomToken();
        	Calendar cal = Calendar.getInstance(); //current date and time      
            cal.add(Calendar.MINUTE, 20); //add days
            double ttl =  (cal.getTimeInMillis() / 1000L);
            Item item = new Item().withPrimaryKey("email", emailAddress).withString("reset_token", token)
            		.withDouble("ttl", ttl);
            table.putItem(item);
            return token;
        } catch (Exception e) {
        	logger.log(e.toString());
        }
        return null;

	}
	
	public static String randomToken() {
        String uuid = UUID.randomUUID().toString();
        return uuid;
    }

}
