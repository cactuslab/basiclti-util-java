package org.imsglobal.pox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import jakarta.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import net.oauth.OAuthAccessor;
import net.oauth.OAuthConsumer;
import net.oauth.OAuthMessage;
import net.oauth.OAuthValidator;
import net.oauth.SimpleOAuthValidator;
import net.oauth.server.OAuthServlet;
import net.oauth.signature.OAuthSignatureMethod;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.exception.OAuthException;
import oauth.signpost.http.HttpParameters;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.imsglobal.lti.XMLMap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class IMSPOXRequest {

	private final static Logger Log = Logger.getLogger(IMSPOXRequest.class .getName());

	public final static String MAJOR_SUCCESS = "success";
	public final static String MAJOR_FAILURE = "failure";
	public final static String MAJOR_UNSUPPORTED = "unsupported";
	public final static String MAJOR_PROCESSING = "processing";

	public final static String [] validMajor = {
		MAJOR_SUCCESS, MAJOR_FAILURE, MAJOR_UNSUPPORTED, MAJOR_PROCESSING };

	public final static String SEVERITY_ERROR = "error";
	public final static String SEVERITY_WARNING = "warning";
	public final static String SEVERITY_STATUS = "status";

	public final static String [] validSeverity = {
		SEVERITY_ERROR, SEVERITY_WARNING, SEVERITY_STATUS };

	public final static String MINOR_FULLSUCCESS ="fullsuccess";
	public final static String MINOR_NOSOURCEDIDS = "nosourcedids";
	public final static String MINOR_IDALLOC = "idalloc";
	public final static String MINOR_OVERFLOWFAIL = "overflowfail";
	public final static String MINOR_IDALLOCINUSEFAIL = "idallocinusefail";
	public final static String MINOR_INVALIDDATAFAIL = "invaliddata";
	public final static String MINOR_INCOMPLETEDATA = "incompletedata";
	public final static String MINOR_PARTIALSTORAGE = "partialdatastorage";
	public final static String MINOR_UNKNOWNOBJECT = "unknownobject";
	public final static String MINOR_DELETEFAILURE = "deletefailure";
	public final static String MINOR_TARGETREADFAILURE = "targetreadfailure";
	public final static String MINOR_SAVEPOINTERROR = "savepointerror";
	public final static String MINOR_SAVEPOINTSYNCERROR = "savepointsyncerror";
	public final static String MINOR_UNKNOWNQUERY = "unknownquery";
	public final static String MINOR_UNKNOWNVOCAB = "unknownvocab";
	public final static String MINOR_TARGETISBUSY = "targetisbusy";
	public final static String MINOR_UNKNOWNEXTENSION = "unknownextension";
	public final static String MINOR_UNAUTHORIZEDREQUEST = "unauthorizedrequest";
	public final static String MINOR_LINKFAILURE = "linkfailure";
	public final static String MINOR_UNSUPPORTED = "unsupported";

	public final static String [] validMinor = {
		MINOR_FULLSUCCESS, MINOR_NOSOURCEDIDS, MINOR_IDALLOC, MINOR_OVERFLOWFAIL,
		MINOR_IDALLOCINUSEFAIL, MINOR_INVALIDDATAFAIL, MINOR_INCOMPLETEDATA,
		MINOR_PARTIALSTORAGE, MINOR_UNKNOWNOBJECT, MINOR_DELETEFAILURE,
		MINOR_TARGETREADFAILURE, MINOR_SAVEPOINTERROR, MINOR_SAVEPOINTSYNCERROR,
		MINOR_UNKNOWNQUERY, MINOR_UNKNOWNVOCAB, MINOR_TARGETISBUSY,
		MINOR_UNKNOWNEXTENSION, MINOR_UNAUTHORIZEDREQUEST, MINOR_LINKFAILURE,
		MINOR_UNSUPPORTED
	} ; 

	public Document postDom = null;
	public Element bodyElement = null;
	public Element headerElement = null;
	public String postBody = null;
	private String header = null;
	private String oauth_body_hash = null;
	private String oauth_consumer_key = null;

	public boolean valid = false;
	private String operation = null;
	public String errorMessage = null;
	public String base_string = null;
	private Map<String,String> bodyMap = null;
	private Map<String,String> headerMap = null;

	public String getOperation()
	{
		return operation;
	}

	public String getOAuthConsumerKey()
	{
		return oauth_consumer_key;
	}

	public String getHeaderVersion()
	{
		return getHeaderItem("/imsx_version");
	}

	public String getHeaderMessageIdentifier()
	{
		return getHeaderItem("/imsx_messageIdentifier");
	}

	public String getHeaderItem(String path)
	{
		if ( getHeaderMap() == null ) return null;
		return headerMap.get(path);
	}

	public Map<String,String> getHeaderMap()
	{
		if ( headerMap != null ) return headerMap;
		if ( headerElement == null ) return null;
		headerMap = XMLMap.getMap(headerElement);
		return headerMap;
	}

	public Map<String,String> getBodyMap()
	{
		if ( bodyMap != null ) return bodyMap;
		if ( bodyElement == null ) return null;
		bodyMap = XMLMap.getMap(bodyElement);
		return bodyMap;
	}

	public String getPostBody()
	{
		return postBody;
	}

	// Normal Constructor
	public IMSPOXRequest(String oauth_consumer_key, String oauth_secret, HttpServletRequest request) 
	{
		loadFromRequest(request);
		if ( ! valid ) return;
		validateRequest(oauth_consumer_key, oauth_secret, request);
	}

	// Constructor for delayed validation
	public IMSPOXRequest(HttpServletRequest request) 
	{
		loadFromRequest(request);
	}

	// Constructor for testing...
	public IMSPOXRequest(String bodyString)
	{
		postBody = bodyString;
		parsePostBody();
	}

	// Load but do not check the authentication
	public void loadFromRequest(HttpServletRequest request) 
	{
		String contentType = request.getContentType();
		if ( ! "application/xml".equals(contentType) ) {
			errorMessage = "Content Type must be application/xml";
			Log.info(errorMessage+"\n"+contentType);
			return;
		}

		setAuthHeader(request.getHeader("Authorization"));
		if ( oauth_body_hash == null ) {
			errorMessage = "Did not find oauth_body_hash";
			Log.info(errorMessage+"\n"+header);
			return;
		}

		try {
			Reader in = request.getReader();
			postBody = readPostBody(in);
		} catch(Exception e) {
			errorMessage = "Could not read message body:"+e.getMessage();
			return;
		}

		validatePostBody();
		if (errorMessage != null) return;

		parsePostBody();
	}

	@SuppressWarnings("deprecation")
	public void setAuthHeader(String header) {
		this.header = header;
		oauth_body_hash = null;
		if ( header != null ) {
			if (header.startsWith("OAuth ")) header = header.substring(5);
			String [] parms = header.split(",");
			for ( String parm : parms ) {
				parm = parm.trim();
				if ( parm.startsWith("oauth_body_hash=") ) {
					String [] pieces = parm.split("\"");
					oauth_body_hash = URLDecoder.decode(pieces[1]);
				}
				if ( parm.startsWith("oauth_consumer_key=") ) {
					String [] pieces = parm.split("\"");
					oauth_consumer_key = URLDecoder.decode(pieces[1]);
				}
			}
		}
	}

	public static String readPostBody(Reader in) throws IOException {
		// System.out.println("OBH="+oauth_body_hash);
		final char[] buffer = new char[0x10000];
		StringBuilder out = new StringBuilder();
		int read;
		do {
			read = in.read(buffer, 0, buffer.length);
			if (read > 0) {
				out.append(buffer, 0, read);
			}
		} while (read >= 0);
		return out.toString();
	}

	public static String getBodyHash(String postBody) throws GeneralSecurityException {
		MessageDigest md = MessageDigest.getInstance("SHA1");
		md.update(postBody.getBytes());
		byte[] output = Base64.encodeBase64(md.digest());
		return new String(output);
	}

	public void validatePostBody() {
		try {
			String hash = getBodyHash(postBody);
			// System.out.println("HASH="+hash);
			if ( ! hash.equals(oauth_body_hash) ) {
				errorMessage = "Body hash does not match header";
				return;
			}
		} catch (Exception e) {
			errorMessage = "Could not compute body hash";
			return;
		}
	}

	public void parsePostBody()
	{
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); 
			dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); 
			DocumentBuilder db = dbf.newDocumentBuilder();
			postDom = db.parse(new ByteArrayInputStream(postBody.getBytes()));
		}catch(Exception e) {
			errorMessage = "Could not parse XML: "+e.getMessage();
			return;
		}

		try {
			XPath xpath = XPathFactory.newInstance().newXPath();
			XPathExpression expr = xpath.compile("/imsx_POXEnvelopeRequest/imsx_POXBody/*");
			Object result = expr.evaluate(postDom, XPathConstants.NODESET);
			NodeList nodes = (NodeList) result;
			bodyElement = (Element) nodes.item(0);
			operation = bodyElement.getNodeName();

			expr = xpath.compile("/imsx_POXEnvelopeRequest/imsx_POXHeader/*");
			result = expr.evaluate(postDom, XPathConstants.NODESET);
			nodes = (NodeList) result;
			headerElement = (Element) nodes.item(0);
		}catch(javax.xml.xpath.XPathExpressionException e) {
			errorMessage = "Could not parse XPATH: "+e.getMessage();
			return;
		}catch(Exception e) {
			errorMessage = "Could not parse input XML: "+e.getMessage();
			return;
		}

		if ( operation == null || bodyElement == null ) {
			errorMessage = "Could not find operation";
			return;
		}
		valid = true;
	}

	// Assumes data is all loaded
	public void validateRequest(String oauth_consumer_key, String oauth_secret, HttpServletRequest request) 
	{
		validateRequest(oauth_consumer_key, oauth_secret, request, null) ;
	}

	public void validateRequest(String oauth_consumer_key, String oauth_secret, HttpServletRequest request, String URL) 
	{
		valid = false;
		OAuthMessage oam = OAuthServlet.getMessage(request, URL);
		OAuthValidator oav = new SimpleOAuthValidator();
		OAuthConsumer cons = new OAuthConsumer("about:blank#OAuth+CallBack+NotUsed", 
				oauth_consumer_key, oauth_secret, null);

		OAuthAccessor acc = new OAuthAccessor(cons);

		try {
			base_string = OAuthSignatureMethod.getBaseString(oam);
		} catch (Exception e) {
			base_string = null;
		}

		try {
			oav.validateMessage(oam,acc);
		} catch(Exception e) {
			errorMessage = "Launch fails OAuth validation: "+e.getMessage();
			return;
		}
		valid = true;
	}

	public static String fetchTag(org.w3c.dom.Element element, String tag)
	{
		try {
			org.w3c.dom.NodeList elements = element.getElementsByTagName(tag);
			int numElements = elements.getLength();
			if (numElements > 0) {
				org.w3c.dom.Element e = (org.w3c.dom.Element)elements.item(0);
				if (e.hasChildNodes()) {
					return e.getFirstChild().getNodeValue();
				}
			}
		} catch (Throwable t) {
			Log.warning(t.getMessage());
			// t.printStackTrace();
		}
		return null;
	}

	public boolean inArray(final String [] theArray, final String theString)
	{
		if ( theString == null ) return false;
		for ( String str : theArray ) {
			if ( theString.equals(str) ) return true;
		}
		return false;
	}

	static final String fatalMessage = 
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
		"<imsx_POXEnvelopeResponse xmlns = \"http://www.imsglobal.org/services/ltiv1p1/xsd/imsoms_v1p0\">\n" +
		"    <imsx_POXHeader>\n" +
		"        <imsx_POXResponseHeaderInfo>\n" + 
		"            <imsx_version>V1.0</imsx_version>\n" +
		"            <imsx_messageIdentifier>%s</imsx_messageIdentifier>\n" + 
		"            <imsx_statusInfo>\n" +
		"                <imsx_codeMajor>failure</imsx_codeMajor>\n" +
		"                <imsx_severity>error</imsx_severity>\n" +
		"                <imsx_description>%s</imsx_description>\n" +
		"                <imsx_operationRefIdentifier>%s</imsx_operationRefIdentifier>" + 
		"            </imsx_statusInfo>\n" +
		"        </imsx_POXResponseHeaderInfo>\n" + 
		"    </imsx_POXHeader>\n" +
		"    <imsx_POXBody/>\n" +
		"</imsx_POXEnvelopeResponse>";

	public static String getFatalResponse(String description)
	{
		return getFatalResponse(description, "unknown");
	}

	public static String getFatalResponse(String description, String message_id)
	{
		Date dt = new Date();
		String messageId = ""+dt.getTime();

		return String.format(fatalMessage, 
				StringEscapeUtils.escapeXml(messageId), 
				StringEscapeUtils.escapeXml(description),
				StringEscapeUtils.escapeXml(message_id)); 
	}

	static final String responseMessage = 
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
		"<imsx_POXEnvelopeResponse xmlns = \"http://www.imsglobal.org/services/ltiv1p1/xsd/imsoms_v1p0\">\n" +
		"  <imsx_POXHeader>\n" +
		"    <imsx_POXResponseHeaderInfo>\n" + 
		"      <imsx_version>V1.0</imsx_version>\n" +
		"      <imsx_messageIdentifier>%s</imsx_messageIdentifier>\n" + 
		"      <imsx_statusInfo>\n" +
		"        <imsx_codeMajor>%s</imsx_codeMajor>\n" +
		"        <imsx_severity>%s</imsx_severity>\n" +
		"        <imsx_description>%s</imsx_description>\n" +
		"        <imsx_messageRefIdentifier>%s</imsx_messageRefIdentifier>\n" +       
		"        <imsx_operationRefIdentifier>%s</imsx_operationRefIdentifier>" + 
		"%s\n"+ 
		"      </imsx_statusInfo>\n" +
		"    </imsx_POXResponseHeaderInfo>\n" + 
		"  </imsx_POXHeader>\n" +
		"  <imsx_POXBody>\n" +
		"%s%s"+
		"  </imsx_POXBody>\n" +
		"</imsx_POXEnvelopeResponse>";

	public String getResponseUnsupported(String desc)
	{
		return getResponse(desc, MAJOR_UNSUPPORTED, null, null, null, null);
	}

	public String getResponseFailure(String desc, Properties minor)
	{
		return getResponse(desc, null, null, null, minor, null);
	}

	public String getResponseFailure(String desc, Properties minor, String bodyString)
	{
		return getResponse(desc, null, null, null, minor, bodyString);
	}

	public String getResponseSuccess(String desc, String bodyString)
	{
		return getResponse(desc, MAJOR_SUCCESS, null, null, null, bodyString);
	}

	public String getResponse(String description, String major, String severity, 
			String messageId, Properties minor, String bodyString)
	{
		StringBuffer internalError = new StringBuffer();
		if ( major == null ) major = MAJOR_FAILURE;
		if ( severity == null && MAJOR_PROCESSING.equals(major) ) severity = SEVERITY_STATUS;
		if ( severity == null && MAJOR_SUCCESS.equals(major) ) severity = SEVERITY_STATUS;
		if ( severity == null ) severity = SEVERITY_ERROR;
		if ( messageId == null ) {
			Date dt = new Date();
			messageId = ""+dt.getTime();
		}

		StringBuffer sb = new StringBuffer();
		if ( minor != null && minor.size() > 0 ) {
			for(Object okey : minor.keySet() ) {
				String key = (String) okey;
				String value = minor.getProperty(key);
				if ( key == null || value == null ) continue;
				if ( !inArray(validMinor, value) ) {
					if ( internalError.length() > 0 ) sb.append(", ");
					internalError.append("Invalid imsx_codeMinorFieldValue="+major);
					continue;
				}
				if ( sb.length() == 0 ) sb.append("\n        <imsx_codeMinor>\n");
				sb.append("          <imsx_codeMinorField>\n            <imsx_codeMinorFieldName>");
				sb.append(key);
				sb.append("</imsx_codeMinorFieldName>\n            <imsx_codeMinorFieldValue>");
				sb.append(StringEscapeUtils.escapeXml(value));
				sb.append("</imsx_codeMinorFieldValue>\n          </imsx_codeMinorField>\n");
			}
			if ( sb.length() > 0 ) sb.append("        </imsx_codeMinor>");
		}
		String minorString = sb.toString();

		if ( ! inArray(validMajor, major) ) {
			if ( internalError.length() > 0 ) sb.append(", ");
			internalError.append("Invalid imsx_codeMajor="+major);
		}
		if ( ! inArray(validSeverity, severity) ) {
			if ( internalError.length() > 0 ) sb.append(", ");
			internalError.append("Invalid imsx_severity="+major);
		}

		if ( internalError.length() > 0 ) {
			description = description + " (Internal error: " + internalError.toString() + ")";
			Log.warning(internalError.toString());
		}

		if ( bodyString == null ) bodyString = "";
		// Trim off XML header
		if ( bodyString.startsWith("<?xml") ) {
			int pos = bodyString.indexOf("<",1);
			if ( pos > 0 ) bodyString = bodyString.substring(pos);
		}
		bodyString = bodyString.trim();
		String newLine = "";
		if ( bodyString.length() > 0 ) newLine = "\n";
		return String.format(responseMessage, 
				StringEscapeUtils.escapeXml(messageId), 
				StringEscapeUtils.escapeXml(major), 
				StringEscapeUtils.escapeXml(severity), 
				StringEscapeUtils.escapeXml(description), 
				StringEscapeUtils.escapeXml(getHeaderMessageIdentifier()), 
				StringEscapeUtils.escapeXml(operation), 
				StringEscapeUtils.escapeXml(minorString), 
				bodyString, newLine); 

	}

	/**
	 * A template string for creating ReplaceResult messages.
	 *
	 * Similar to {@link #replaceResultMessage}, except has support for messageIdentifier
	 *
	 * Use like:
	 * <pre>
	 *     String.format(
	 *       ReplaceResultMessageTemplate,
	 *       messageId,
	 *       sourcedId,
	 *       resultScore,
	 *       resultDataXml
	 *     )
	 * </pre>
	 *
	 *
	 */
	public static final String ReplaceResultMessageTemplate =
			"<?xml version = \"1.0\" encoding = \"UTF-8\"?>"+
			"<imsx_POXEnvelopeRequest xmlns=\"http://www.imsglobal.org/services/ltiv1p1/xsd/imsoms_v1p0\">"+
			"	<imsx_POXHeader>"+
			"		<imsx_POXRequestHeaderInfo>"+
			"			<imsx_version>V1.0</imsx_version>"+
			"			<imsx_messageIdentifier>%s</imsx_messageIdentifier>"+
			"		</imsx_POXRequestHeaderInfo>"+
			"	</imsx_POXHeader>"+
			"	<imsx_POXBody>"+
			"		<replaceResultRequest>"+
			"			<resultRecord>"+
			"				<sourcedGUID>"+
			"					<sourcedId>%s</sourcedId>"+
			"				</sourcedGUID>"+
			"				<result>"+
			"					<resultScore>"+
			"						<language>en</language>"+
			"						<textString>%s</textString>"+
			"					</resultScore>"+
			"					%s"+
			"				</result>"+
			"			</resultRecord>"+
			"		</replaceResultRequest>"+
			"	</imsx_POXBody>"+
			"</imsx_POXEnvelopeRequest>";

	/**
	 * @deprecated use {@link #ReplaceResultMessageTemplate} instead.
	 */
	@Deprecated
	public static final String replaceResultMessage =
			"<?xml version = \"1.0\" encoding = \"UTF-8\"?>"+
			"<imsx_POXEnvelopeRequest xmlns=\"http://www.imsglobal.org/services/ltiv1p1/xsd/imsoms_v1p0\">"+
			"	<imsx_POXHeader>"+
			"		<imsx_POXRequestHeaderInfo>"+
			"			<imsx_version>V1.0</imsx_version>"+
			"		</imsx_POXRequestHeaderInfo>"+
			"	</imsx_POXHeader>"+
			"	<imsx_POXBody>"+
			"		<replaceResultRequest>"+
			"			<resultRecord>"+
			"				<sourcedGUID>"+
			"					<sourcedId>%s</sourcedId>"+
			"				</sourcedGUID>"+
			"				<result>"+
			"					<resultScore>"+
			"						<language>en</language>"+
			"						<textString>%s</textString>"+
			"					</resultScore>"+
			"					%s"+
			"				</result>"+
			"			</resultRecord>"+
			"		</replaceResultRequest>"+
			"	</imsx_POXBody>"+
			"</imsx_POXEnvelopeRequest>";
	
	static final String resultDataText = "<resultData><text>%s</text></resultData>";
	
	static final String resultDataUrl = "<resultData><url>%s</url></resultData>";
	
	public static void sendReplaceResult(String url, String key, String secret, String sourcedid, String score) throws IOException, OAuthException, GeneralSecurityException {
		sendReplaceResult(url, key, secret, sourcedid, score, null);
	}

	public static void sendReplaceResult(String url, String key, String secret, String sourcedid, String score, String resultData) throws IOException, OAuthException, GeneralSecurityException {
		sendReplaceResult(url, key, secret, sourcedid, score, resultData, false);
	}

	public static void sendReplaceResult(String url, String key, String secret, String sourcedid, String score, String resultData, Boolean isUrl) throws IOException, OAuthException, GeneralSecurityException {
		HttpPost request = buildReplaceResult(url, key, secret, sourcedid, score, resultData, isUrl);
		DefaultHttpClient client = new DefaultHttpClient();
		HttpResponse response = client.execute(request);
		if (response.getStatusLine().getStatusCode() >= 400) {
			String detail = EntityUtils.toString(response.getEntity());
			throw new IMSPOXException(response.getStatusLine().getStatusCode(),
					response.getStatusLine().getReasonPhrase(),
					detail);
		}
	}

	public static class IMSPOXException extends IOException {

		private final String detail;

		public IMSPOXException(int statusCode, String message, String detail) {
			super(statusCode + " " + message);
			this.detail = detail;
		}

		public String getDetail() {
			return this.detail;
		}

	}

	public static HttpPost buildReplaceResult(String url, String key, String secret, String sourcedid, String score, String resultData, Boolean isUrl) throws IOException, OAuthException, GeneralSecurityException {
		return buildReplaceResult(url, key, secret, sourcedid, score, resultData, isUrl, null);
	}

	public static HttpPost buildReplaceResult(String url, String key, String secret, String sourcedid,
		String score, String resultData, Boolean isUrl, String messageId) throws IOException, OAuthException, GeneralSecurityException
	{
		String dataXml = "";
		if (resultData != null) {
			String format = isUrl ? resultDataUrl : resultDataText;
			dataXml = String.format(format, StringEscapeUtils.escapeXml(resultData));
		}

		String messageIdentifier = StringUtils.isBlank(messageId) ? String.valueOf(new Date().getTime()) : messageId;
		String xml = String.format(ReplaceResultMessageTemplate,
			StringEscapeUtils.escapeXml(messageIdentifier),
			StringEscapeUtils.escapeXml(sourcedid),
			StringEscapeUtils.escapeXml(score),
			dataXml);

		HttpParameters parameters = new HttpParameters();
		String hash = getBodyHash(xml);
		parameters.put("oauth_body_hash", URLEncoder.encode(hash, "UTF-8"));

		CommonsHttpOAuthConsumer signer = new CommonsHttpOAuthConsumer(key, secret);
		HttpPost request = new HttpPost(url);
		request.setHeader("Content-Type", "application/xml");
		request.setEntity(new StringEntity(xml, "UTF-8"));
		signer.setAdditionalParameters(parameters);
		signer.sign(request);
		return request;
	}

	/*

roleType:
Learner
Instructor
ContentDeveloper
Member
Manager
Mentor
Administrator
TeachingAssistant

fieldType:
Boolean
Integer
Real
String

<readMembershipResponse
xmlns="http://www.imsglobal.org/services/lis/mms2p0/wsdl11/sync/imsmms_v2p0">
<membershipRecord>
<sourcedId>GUID.TYPE</sourcedId>
<membership>
<collectionSourcedId>GUID.TYPE</collectionSourcedId>
<membershipIdType>MEMBERSHIPIDTYPE.TYPE</membershipIdType>
<member>
<personSourcedId>GUID.TYPE</personSourcedId>
<role>
<roleType>STRING</roleType>
<subRole>STRING</subRole>
<timeFrame>
<begin>DATETIME</begin>
<end>DATETIME</end>
<restrict>BOOLEAN</restrict>
<adminPeriod>
<language>LANGUAGESET.TYPE</language>
<textString>STRING</textString>
</adminPeriod>
</timeFrame>
<status>STATUS.TYPE</status>
<dateTime>DATETIME</dateTime>
<dataSource>GUID.TYPE</dataSource>
<recordInfo>
<extensionField>
<fieldName>STRING</fieldName>
<fieldType>FIELDTYPE.TYPE</fieldType>
<fieldValue>STRING</fieldValue>
</extensionField>
</recordInfo>
<extension>
<extensionField>
<fieldName>STRING</fieldName>
<fieldType>FIELDTYPE.TYPE</fieldType>
<fieldValue>STRING</fieldValue>
</extensionField>
</extension>
</role>
</member>
<creditHours>INTEGER</creditHours>
<dataSource>GUID.TYPE</dataSource>
</membership>
</membershipRecord>
</readMembershipResponse>
	 */
}
