package in.nerdworks.azureclient;

import com.google.common.base.Function;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.Builder;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.compute.ComputeManagementService;
import com.microsoft.windowsazure.management.compute.HostedServiceOperations;
import com.microsoft.windowsazure.management.compute.models.HostedServiceListResponse;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;
import java.io.*;
import java.util.Map;
import java.util.ServiceLoader;

public class ListHostedServicesAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        String publishSettings = Messages.showInputDialog(anActionEvent.getProject(),
                "Enter path to subscription settings file:",
                "Subscription Settings", null);
        String subscriptionId = Messages.showInputDialog(anActionEvent.getProject(),
                "Enter subscription ID:",
                "Subscription ID", null);
        String title = "Hosted Services";

        StringWriter sw = new StringWriter();
        BufferedWriter writer = new BufferedWriter(sw);

        try {
            // add a password to the management certs in the publish settings
            String processedPath = processPublishSettingsFile(publishSettings);
            Configuration configuration = createConfiguration(subscriptionId, processedPath);

            for(Map.Entry<String, Object> entry : configuration.getProperties().entrySet()) {
                writer.write(entry.getKey() + " : " + entry.getValue().toString());
                writer.newLine();
            }
            writer.newLine();

            ComputeManagementClient client = createComputeManagementClient(configuration);
            HostedServiceOperations hostedServicesOperations = client.getHostedServicesOperations();
            HostedServiceListResponse response = hostedServicesOperations.list();

            writer.write("Hosted Services List:");
            writer.newLine();
            for (HostedServiceListResponse.HostedService hostedService : response) {
                writer.write(String.format("%s : %s" + File.separator, hostedService.getServiceName(), hostedService.getUri().toString()));
                writer.newLine();
            }
            writer.flush();
        }
        catch (Exception e) {
            e.printStackTrace();

            try {
                writer.write(e.toString());
                writer.newLine(); writer.newLine();
            } catch (IOException e1) {
                e1.printStackTrace();
            }

            PrintWriter pw = new PrintWriter(writer);
            e.printStackTrace(pw);
            title = "Whoops!";

            try {
                writer.flush();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }

        Messages.showInfoMessage(anActionEvent.getProject(), sw.toString(), title);
    }

    private Configuration createConfiguration(final String subscriptionId, final String processedPath) {
        return runWithClassLoader(ComputeManagementService.class.getClassLoader(), null, new Function<Object, Configuration>() {
            @Override
            public Configuration apply(Object o) {
                try {
                    return PublishSettingsLoader.createManagementConfiguration(
                            processedPath, subscriptionId, OpenSSLHelper.PASSWORD);
                } catch (IOException e) {
                    return null;
                }
            }
        });
    }

    private <T> T runWithClassLoader(ClassLoader classLoader, Object input, Function<Object, T> runnable) {
        // the azure sdk for java uses dependency injection to resolve objects and
        // it needs to use it's own class loader to successfully resolve objects
        Thread currentThread = Thread.currentThread();
        ClassLoader currentClassLoader = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(classLoader);

        try {
            return runnable.apply(input);
        }
        finally {
            currentThread.setContextClassLoader(currentClassLoader);
        }
    }

    private ComputeManagementClient createComputeManagementClient(final Configuration configuration) {
        return runWithClassLoader(ComputeManagementService.class.getClassLoader(), null, new Function<Object, ComputeManagementClient>() {
            @Override
            public ComputeManagementClient apply(Object o) {
                return ComputeManagementService.create(configuration);
            }
        });
    }

    private String processPublishSettingsFile(String filePath) throws IOException, InterruptedException, SAXException, ParserConfigurationException, XPathExpressionException, TransformerException {
        String xml = readFile(filePath);
        String processedXml = OpenSSLHelper.processCertificate(xml);

        File processedFilePath = new File(System.getProperty("java.io.tmpdir") + File.separator + "processed-publish-settings.publishsettings");
        PrintWriter writer = null;

        try {
            writer = new PrintWriter(processedFilePath);
            writer.print(processedXml);
        }
        finally {
            if(writer != null) {
                writer.close();
            }
        }

        return processedFilePath.getPath();
    }

    private String readFile( String file ) throws IOException {
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(file));
            String line = null;
            StringBuilder stringBuilder = new StringBuilder();
            String ls = System.getProperty("line.separator");

            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
                stringBuilder.append(ls);
            }

            return stringBuilder.toString();
        }
        finally {
            if(reader != null) {
                reader.close();
            }
        }
    }
}
