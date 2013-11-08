package cz.mzk.k4.tools.utils.fedoraUtils;

import cz.mzk.k4.tools.workers.UuidWorker;
import cz.mzk.k4.tools.utils.fedoraUtils.domain.DigitalObjectModel;
import cz.mzk.k4.tools.utils.fedoraUtils.domain.FedoraNamespaces;
import cz.mzk.k4.tools.utils.fedoraUtils.exception.ConnectionException;
import cz.mzk.k4.tools.utils.fedoraUtils.exception.CreateObjectException;
import cz.mzk.k4.tools.utils.fedoraUtils.exception.LexerException;
import cz.mzk.k4.tools.utils.fedoraUtils.util.IOUtils;
import cz.mzk.k4.tools.utils.fedoraUtils.util.PIDParser;
import cz.mzk.k4.tools.utils.fedoraUtils.util.RESTHelper;
import cz.mzk.k4.tools.utils.fedoraUtils.util.XMLUtils;
import org.apache.log4j.Logger;
import org.fedora.api.FedoraAPIA;
import org.fedora.api.FedoraAPIM;
import org.fedora.api.FedoraAPIMService;
import org.fedora.api.RelationshipTuple;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.ws.BindingProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * @author: Martin Rumanek, incad
 * @version: 9/23/13
 */
public class FedoraUtils {

    static final String CONF_FILE_NAME = "k4_tools_config.properties";
    /**
     * The Constant LOGGER.
     */
    private static final Logger LOGGER = Logger.getLogger(FedoraUtils.class);
    private static String FEDORA_URL = "";//"http://fedora.mzk.cz/fedora/";
    private static String USER = "";//fedora user
    private static String PASS = "";//fedora password

    /** The API mport. */
    private FedoraAPIM APIMport;

    /** The API aport. */
    private FedoraAPIA APIAport;

    public FedoraUtils() {
        String home = System.getProperty("user.home");
        File f = new File(home + "/" + CONF_FILE_NAME);


        Properties properties = new Properties();

        InputStream inputStream;
        try {
            inputStream = new FileInputStream(f);
            properties.load(inputStream);
        } catch (IOException ex) {
            LOGGER.log(null, ex);
        }

        USER = properties.getProperty("fedora.username");
        PASS = properties.getProperty("fedora.password");
        FEDORA_URL = properties.getProperty("fedora.url");


    }

    @SuppressWarnings("serial")
    public void applyToAllUuidOfModel(String model, UuidWorker worker) {
        List<RelationshipTuple> triplets = FedoraUtils.getObjectPidsFromModel(model);

        if (triplets != null) {
            for (final RelationshipTuple triplet : triplets) {
                if (triplet.getSubject().contains("uuid")
                        && triplet.getSubject().contains(Constants.FEDORA_INFO_PREFIX)) {

                    final String childUuid =
                            triplet.getSubject().substring((Constants.FEDORA_INFO_PREFIX).length());

                    if (!childUuid.contains("/")) {
                        LOGGER.info("Worker is running on " + childUuid);
                        worker.run(childUuid);
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    public static ArrayList<ArrayList<String>> getAllChildren(String uuid) {
        List<RelationshipTuple> triplets = FedoraUtils.getObjectPids(uuid);
        ArrayList<ArrayList<String>> children = new ArrayList<ArrayList<String>>();

        if (triplets != null) {
            for (final RelationshipTuple triplet : triplets) {
                if (triplet.getObject().contains("uuid")
                        && triplet.getObject().contains(Constants.FEDORA_INFO_PREFIX)) {

                    final String childUuid =
                            triplet.getObject().substring((Constants.FEDORA_INFO_PREFIX).length());

                    if (!childUuid.contains("/")) {
                        children.add(new ArrayList<String>() {

                            {
                                add(childUuid);
                                add(triplet.getPredicate());
                            }
                        });
                    }
                }
            }
        }
        return children;
    }

    public static List<String> getChildren(String uuid, DigitalObjectModel model) throws IOException {
       return getChildren(uuid, new ArrayList<String>(), model);
    }

    private static List<String> getChildren(String uuid, List<String> uuidList, DigitalObjectModel model) throws IOException {
        if (model.equals(FedoraUtils.getModel(uuid))) {
            uuidList.add(uuid);
        }
        DigitalObjectModel parentModel = null;
        ArrayList<ArrayList<String>> children = getAllChildren(uuid);

        if (children != null) {
            for (ArrayList<String> child : children) {
                getChildren(child.get(0), uuidList, model);
            }
        }

        return uuidList;
    }

    /**
     * Gets the object pids.
     *
     * @param subjectPid the subject pid
     * @return the object pids
     */
    public static List<RelationshipTuple> getObjectPids(String subjectPid) {
        return getSubjectOrObjectPids("%3Cinfo:fedora/" + subjectPid + "%3E%20*%20*");
    }

    private static List<RelationshipTuple> getObjectPidsFromModel(String model) {
        return getSubjectOrObjectPids("%20*%20*%20%3Cinfo:fedora/" + model + "%3E");
    }

    private static List<RelationshipTuple> getPagesOfRootUuid(String uuid) {
        return getSubjectOrObjectPids("%20*%20*%20%3Cinfo:fedora/" + uuid + "%3E");
        // <info:fedora/uuid:542e41d0-dd86-11e2-9923-005056827e52> <http://www.nsdl.org/ontologies/relationships#hasPage> *
    }

    private static List<RelationshipTuple> getSubjectOrObjectPids(String restOfCommand) {
        List<RelationshipTuple> retval = new ArrayList<RelationshipTuple>();
//        String command =
//                configuration.getFedoraHost() + "/risearch?type=triples&lang=spo&format=N-Triples&query="
//                        + restOfCommand;
        String command =
                FEDORA_URL + "/risearch?type=triples&lang=spo&format=N-Triples&query="
                        + restOfCommand;
        InputStream stream = null;
        try {
            stream =
                    RESTHelper.get(command,
                            USER, PASS,
//                            configuration.getFedoraLogin(),
//                            configuration.getFedoraPassword(),
                            false);
            if (stream == null) return null;
            String result = IOUtils.readAsString(stream, Charset.forName("UTF-8"), true);
            String[] lines = result.split("\n");
            for (String line : lines) {
                String[] tokens = line.split(" ");
                if (tokens.length < 3) continue;
                try {
                    RelationshipTuple tuple = new RelationshipTuple();
                    tuple.setSubject(tokens[0].substring(1, tokens[0].length() - 1));
                    tuple.setPredicate(tokens[1].substring(1, tokens[1].length() - 1));
                    tuple.setObject(tokens[2].substring(1, tokens[2].length() - 1));
                    tuple.setIsLiteral(false);
                    retval.add(tuple);
                } catch (Exception ex) {
                    LOGGER.info("Problem parsing RDF, skipping line:" + Arrays.toString(tokens) + " : " + ex);
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            if (stream != null) try {
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return retval;
    }

    public static DigitalObjectModel getModel(String uuid) throws IOException {
        DigitalObjectModel model = null;
        try {
            model = getDigitalObjectModel(uuid);
        } catch (ConnectionException e) {
            LOGGER.error("Digital object " + uuid + " is not in the repository. " + e.getMessage());
            throw e;
        } catch (IOException e) {
            LOGGER.warn("Could not get model of object " + uuid + ". Using generic model handler.", e);
            throw e;
        }
        return model;
    }

    /*
 * (non-Javadoc)
 * @see cz.mzk.editor.server.fedora.FedoraAccess#getKrameriusModel
 * (java.lang.String)
 */

    public static Document getRelsExt(String uuid) throws IOException {
        String relsExtUrl = relsExtUrl(uuid);
        LOGGER.debug("Reading rels ext +" + relsExtUrl);
        InputStream docStream =
                RESTHelper.get(relsExtUrl,
                        USER,
                        PASS,
                        true);
        if (docStream == null) {
            throw new ConnectionException("Cannot get RELS EXT stream.");
        }
        try {
            return XMLUtils.parseDocument(docStream, true);
        } catch (ParserConfigurationException e) {
            LOGGER.error(e.getMessage(), e);
            throw new IOException(e);
        } catch (SAXException e) {
            LOGGER.error(e.getMessage(), e);
            throw new IOException(e);
        } finally {
            docStream.close();
        }
    }

    public static String relsExtUrl(String uuid) {
        String url = FEDORA_URL + "/get/" + uuid + "/RELS-EXT";
        return url;
    }

    public static DigitalObjectModel getDigitalObjectModel(String uuid) throws IOException {
        return getDigitalObjectModel(getRelsExt(uuid));
    }

    public static DigitalObjectModel getDigitalObjectModel(Document relsExt) {
        try {
            Element foundElement =
                    XMLUtils.findElement(relsExt.getDocumentElement(),
                            "hasModel",
                            FedoraNamespaces.FEDORA_MODELS_URI);
            if (foundElement != null) {
                String sform = foundElement.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                PIDParser pidParser = new PIDParser(sform);
                pidParser.disseminationURI();
                DigitalObjectModel model = DigitalObjectModel.parseString(pidParser.getObjectId());
                return model;
            } else
                throw new IllegalArgumentException("cannot find model of ");
        } catch (DOMException e) {
            LOGGER.error(e.getMessage(), e);
            throw new IllegalArgumentException(e);
        } catch (LexerException e) {
            LOGGER.error(e.getMessage(), e);
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Gets the fedora datastreams list.
     *
     * @param uuid the uuid
     * @return the fedora datastreams list
     */
    public static String getFedoraDatastreamsList(String uuid) {
        String datastreamsListPath =
                FEDORA_URL + "/objects/" + uuid + "/datastreams?format=xml";
        return datastreamsListPath;
    }

    public String getOcr(String uuid) {
        String ocrUrl = ocr(uuid);
        LOGGER.debug("Reading OCR +" + ocrUrl);
        InputStream docStream = null;
        try {
            docStream =
                    RESTHelper.get(ocrUrl,
                            USER,
                            PASS,
                            true);
            if (docStream == null) return null;
        } catch (IOException e) {
            // ocr is not available
            e.printStackTrace();
            return null;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(docStream));
        StringBuilder sb = new StringBuilder();
        String line = null;
        try {
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.error("Reading ocr +" + ocrUrl, e);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    LOGGER.error("Closing stream +" + ocrUrl, e);
                    e.printStackTrace();
                } finally {
                    br = null;
                }
            }
            try {
                if (docStream != null) docStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                docStream = null;
            }
        }
        return sb.toString();
    }

    public boolean setOcr(String uuid, String ocr) throws CreateObjectException {
        return insertManagedDatastream(Constants.DATASTREAM_ID.TEXT_OCR, uuid, ocr, false, "text/plain");
    }

    /**
     * Ocr.
     *
     * @param uuid the uuid
     * @return the string
     */
    private String ocr(String uuid) {
        String fedoraObject =
                FEDORA_URL + "/objects/" + uuid + "/datastreams/TEXT_OCR/content";
        return fedoraObject;
    }

    /**
     * Insert managed datastream.
     *
     * @param dsId              the ds id
     * @param uuid              the uuid
     * @param filePathOrContent the file path or content
     * @param isFile            the is file
     * @param mimeType          the mime type
     * @return true, if successful
     * @throws CreateObjectException the create object exception
     */
    private boolean insertManagedDatastream(Constants.DATASTREAM_ID dsId,
                                            String uuid,
                                            String filePathOrContent,
                                            boolean isFile,
                                            String mimeType) throws CreateObjectException {

        String prepUrl =
                "/objects/" + (uuid.contains("uuid:") ? uuid : "uuid:".concat(uuid)) + "/datastreams/"
                        + dsId.getValue() + "?controlGroup=M&versionable=true&dsState=A&mimeType=" + mimeType;

        boolean success;
        String url = FEDORA_URL.concat(prepUrl);
        try {
            if (isFile) {
                success =
                        RESTHelper.post(url,
                                new FileInputStream(new File(filePathOrContent)),
                                USER,
                                PASS,
                                false);
            } else {
                success = RESTHelper.post(url, filePathOrContent, USER, PASS, false);
            }
        } catch (FileNotFoundException e) {
            LOGGER.error(e.getMessage());
            e.printStackTrace();
            throw new CreateObjectException("Unable to post "
                    + (isFile ? ("a file: " + filePathOrContent + " as a ") : "")
                    + "managed datastream to the object: " + uuid);
        }

        if (success) {
            LOGGER.info("An " + dsId.getValue() + (isFile ? (" file: " + filePathOrContent) : "")
                    + " has been inserted to the digital object: " + uuid + " as a " + dsId.getValue()
                    + " datastream.");

            return true;
        } else {
            LOGGER.error("An error occured during inserting an " + dsId.getValue()
                    + (isFile ? (" file: " + filePathOrContent) : "") + " to the digital object: " + uuid
                    + " as a " + dsId.getValue() + " datastream.");
            return false;

        }
    }


    private void initAPIM() {
        Authenticator.setDefault(new Authenticator() {

            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USER, PASS.toCharArray());
            }
        });

        FedoraAPIMService APIMservice = null;
        try {
            APIMservice =
                    new FedoraAPIMService(new URL(FEDORA_URL + "/wsdl?api=API-M"),
                            new QName("http://www.fedora.info/definitions/1/0/api/",
                                    "Fedora-API-M-Service"));
        } catch (MalformedURLException e) {
            LOGGER.error("InvalidURL API-M:" + e);
            throw new RuntimeException(e);
        }
        APIMport = APIMservice.getPort(FedoraAPIM.class);
        ((BindingProvider) APIMport).getRequestContext().put(BindingProvider.USERNAME_PROPERTY, USER);
        ((BindingProvider) APIMport).getRequestContext().put(BindingProvider.PASSWORD_PROPERTY, PASS);

    }

    public FedoraAPIM getAPIM() {
        if (APIMport == null) {
            initAPIM();
        }
        return APIMport;
    }

    public void insertFoxml(String path) {
        // getAPIM().ingest();
    }




}
