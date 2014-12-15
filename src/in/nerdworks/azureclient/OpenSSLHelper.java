package in.nerdworks.azureclient;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.util.io.FileUtil;
import org.apache.xerces.dom.DeferredElementImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.*;

public class OpenSSLHelper {
    public static String PASSWORD = "Java6NeedsPwd";
    String path;

    public static boolean existsOpenSSL() {
        String path = getOpenSSLPath();
        return (path != null && !path.isEmpty());
    }

    private static String getOpenSSLPath() {

        PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();

        if (propertiesComponent.isValueSet("MSOpenSSLPath")) {
            return propertiesComponent.getValue("MSOpenSSLPath");
        } else {
            try {
                String mOsVersion = System.getProperty("os.name");
                String osName = mOsVersion.split(" ")[0];

                String cmd = osName.equals("Windows") ? "where openssl" : "which openssl";
                Process p = Runtime.getRuntime().exec(cmd);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        p.getInputStream()));


                return new File(reader.readLine()).getParent();

            } catch (Throwable e) {
                return null;
            }
        }
    }

    public static Object getXMLValue(String xml, String xQuery, QName resultType) throws XPathExpressionException, IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document doc = db.parse(new InputSource(new StringReader(xml)));

        XPathFactory xPathfactory = XPathFactory.newInstance();

        XPath xPath = xPathfactory.newXPath();
        XPathExpression xPathExpression = xPath.compile(xQuery);
        return xPathExpression.evaluate(doc, resultType);
    }

    public static String getAttributeValue(Node node, String attributeName) {
        Node n = node.getAttributes().getNamedItem(attributeName);
        return (n == null) ? null : n.getNodeValue();
    }

    public static String getChildNodeValue(Node node, String elementName) {
        return ((DeferredElementImpl) node).getElementsByTagName(elementName).item(0).getTextContent();
    }

    public static String processCertificate(String xmlPublishSettings) throws SAXException, ParserConfigurationException, XPathExpressionException, IOException, InterruptedException, TransformerException {
        Node publishProfileNode = ((NodeList) getXMLValue(xmlPublishSettings, "/PublishData/PublishProfile", XPathConstants.NODESET)).item(0);
        String version = getAttributeValue(publishProfileNode, "SchemaVersion");

        boolean isFirstVersion = (version == null || Float.parseFloat(version) < 2);

        NodeList subscriptionList = (NodeList) getXMLValue(xmlPublishSettings, "//Subscription", XPathConstants.NODESET);

        Document ownerDocument = null;

        for (int i = 0; i != subscriptionList.getLength(); i++) {

            //Gets the pfx info
            Element node = (Element) subscriptionList.item(i);
            ownerDocument = node.getOwnerDocument();
            String pfx = getAttributeValue(isFirstVersion ? publishProfileNode : node, "ManagementCertificate");
            byte[] decodedBuffer = new BASE64Decoder().decodeBuffer(pfx);

            //Create pfxFile
            File tmpPath = new File(System.getProperty("java.io.tmpdir") + File.separator + "tempAzureCert");
            tmpPath.mkdirs();
            tmpPath.setWritable(true);

            File pfxFile = new File(tmpPath.getPath() + File.separator + "temp.pfx");

            pfxFile.createNewFile();
            pfxFile.setWritable(true);

            FileOutputStream pfxOutputStream = new FileOutputStream(pfxFile);
            pfxOutputStream.write(decodedBuffer);
            pfxOutputStream.flush();
            pfxOutputStream.close();

            String path = getOpenSSLPath();
            if (!path.endsWith(File.separator)) {
                path = path + File.separator;
            }

            String mOsVersion = System.getProperty("os.name");
            String osName = mOsVersion.split(" ")[0];

            String optionalQuotes = osName.equals("Windows") ? "\"" : "";

            //Export to pem with OpenSSL
            runCommand(optionalQuotes + path + "openssl" + optionalQuotes + " pkcs12 -in temp.pfx -out temp.pem -nodes -password pass:", tmpPath);

            //Export to pfx again and change password
            runCommand(optionalQuotes + path + "openssl" + optionalQuotes + " pkcs12 -export -out temppwd.pfx -in temp.pem -password pass:" + PASSWORD, tmpPath);

            //Read file and replace pfx with password protected pfx
            File pwdPfxFile = new File(tmpPath.getPath() + File.separator + "temppwd.pfx");
            byte[] buf = new byte[(int) pwdPfxFile.length()];
            FileInputStream pfxInputStream = new FileInputStream(pwdPfxFile);
            pfxInputStream.read(buf, 0, (int) pwdPfxFile.length());
            pfxInputStream.close();

            FileUtil.delete(tmpPath);

            node.setAttribute("ManagementCertificate", new BASE64Encoder().encode(buf).replace("\r", "").replace("\n", ""));

            if (isFirstVersion)
                node.setAttribute("ServiceManagementUrl", getAttributeValue(publishProfileNode, "Url"));
        }

        if (ownerDocument == null)
            return null;

        Transformer tf = TransformerFactory.newInstance().newTransformer();
        Writer out = new StringWriter();
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        tf.transform(new DOMSource(ownerDocument), new StreamResult(out));

        return out.toString();
    }

    private static void runCommand(String cmd, File path) throws IOException, InterruptedException {
        final Process p;

        Runtime runtime = Runtime.getRuntime();
        p = runtime.exec(
                cmd,
                null, //new String[] {"PRECOMPILE_STREAMLINE_FILES=1"},
                path);

        String errResponse = new String(FileUtil.adaptiveLoadBytes(p.getErrorStream()));

        if (p.waitFor() != 0) {
            IOException ex = new IOException("Error executing OpenSSL command \n" + errResponse);
            ex.printStackTrace();

            throw ex;
        }
    }
}
