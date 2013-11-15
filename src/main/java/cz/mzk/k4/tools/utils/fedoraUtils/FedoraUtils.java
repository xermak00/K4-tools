package cz.mzk.k4.tools.utils.fedoraUtils;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import cz.mzk.k4.tools.utils.AccessProvider;
import cz.mzk.k4.tools.utils.fedoraUtils.domain.DigitalObjectModel;
import cz.mzk.k4.tools.utils.fedoraUtils.domain.FedoraNamespaces;
import cz.mzk.k4.tools.utils.fedoraUtils.exception.ConnectionException;
import cz.mzk.k4.tools.utils.fedoraUtils.exception.CreateObjectException;
import cz.mzk.k4.tools.utils.fedoraUtils.exception.LexerException;
import cz.mzk.k4.tools.utils.fedoraUtils.util.PIDParser;
import cz.mzk.k4.tools.utils.fedoraUtils.util.XMLUtils;
import cz.mzk.k4.tools.workers.UuidWorker;
import org.apache.log4j.Logger;
import org.fedora.api.RelationshipTuple;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * @author: Martin Rumanek, incad
 * @version: 9/23/13
 */
public class FedoraUtils {

    private static final Logger LOGGER = Logger.getLogger(FedoraUtils.class);
    private AccessProvider accessProvider;

    public FedoraUtils(AccessProvider accessProvider) {
        this.accessProvider = accessProvider;
    }

    public void applyToAllUuidOfModel(DigitalObjectModel model, final UuidWorker worker) {
        applyToAllUuidOfModel(model, worker, 1);
    }

    public void applyToAllUuidOfModel(DigitalObjectModel model, final UuidWorker worker, Integer maxThreads) {
        List<RelationshipTuple> triplets = getObjectPidsFromModel(model);
        applyToAllUuid(triplets, worker, maxThreads);
    }

    public void applyToAllUuidOfStateDeleted(final UuidWorker worker) {
        List<RelationshipTuple> triplets = null;
        try {
            triplets = getObjectsPidsStateDeleted();
        } catch (UnsupportedEncodingException e) {
            LOGGER.error("Unsupported encoding");
        }
        applyToAllUuid(triplets, worker, 1);
    }

    public void applyToAllUuid(List<RelationshipTuple> triplets, final UuidWorker worker) {
        applyToAllUuid(triplets, worker, 1);
    }

    public void applyToAllUuid(List<RelationshipTuple> triplets, final UuidWorker worker, Integer maxThreads) {

        final Semaphore semaphore = new Semaphore(maxThreads);

        if (triplets != null) {
            for (final RelationshipTuple triplet : triplets) {
                if (triplet.getSubject().contains("uuid")
                        && triplet.getSubject().contains(Constants.FEDORA_INFO_PREFIX)) {

                    final String childUuid =
                            triplet.getSubject().substring((Constants.FEDORA_INFO_PREFIX).length());

                    if (!childUuid.contains("/")) {
                        try {
                            semaphore.acquire();
                            new Thread() {
                                public void run() {
                                    LOGGER.debug("Worker is running on " + childUuid);
                                    worker.run(childUuid);
                                    semaphore.release();
                                }
                            }.start();
                        } catch (InterruptedException e) {
                            semaphore.release();
                            LOGGER.error("Worker on " + childUuid + " was interrupted");
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    public ArrayList<ArrayList<String>> getAllChildren(String uuid) {
        List<RelationshipTuple> triplets = getObjectPids(uuid);
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

    public List<String> getChildrenUuids(String uuid, DigitalObjectModel model) throws IOException {
        return getChildrenUuids(uuid, new ArrayList<String>(), model);
    }

    private List<String> getChildrenUuids(String uuid, List<String> uuidList, DigitalObjectModel model) throws IOException {
        if (model.equals(getModel(uuid))) {
            uuidList.add(uuid);
        }
        DigitalObjectModel parentModel = null;
        ArrayList<ArrayList<String>> children = getAllChildren(uuid);

        if (children != null) {
            for (ArrayList<String> child : children) {
                getChildrenUuids(child.get(0), uuidList, model);
            }
        }

        return uuidList;
    }

    public List<String> getChildrenUuids(String uuid) throws IOException {
        List<String> list = getChildrenUuids(uuid, new ArrayList<String>());
        list.remove(uuid);
        return list;
    }

    private List<String> getChildrenUuids(String uuid, List<String> uuidList) throws IOException {
        uuidList.add(uuid);
        ArrayList<ArrayList<String>> children = getAllChildren(uuid);
        if (children != null) {
            for (ArrayList<String> child : children) {
                getChildrenUuids(child.get(0), uuidList);
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
    public List<RelationshipTuple> getObjectPids(String subjectPid) {
        // <info:fedora/[uuid:...]> * *
        return getSubjectOrObjectPids("%3Cinfo:fedora/" + subjectPid + "%3E%20*%20*");
    }

    private List<RelationshipTuple> getObjectPidsFromModel(DigitalObjectModel model) {
        // * * <info:fedora//model:[model]>
        return getSubjectOrObjectPids("%20*%20*%20%3Cinfo:fedora/model:" + model.getValue() + "%3E");
    }

    private List<RelationshipTuple> getPagesOfRootUuid(String uuid) {
        // <info:fedora/uuid:542e41d0-dd86-11e2-9923-005056827e52> <http://www.nsdl.org/ontologies/relationships#hasPage> *
        return getSubjectOrObjectPids("%20*%20*%20%3Cinfo:fedora/" + uuid + "%3E");
    }

    private List<RelationshipTuple> getObjectsPidsStateDeleted() throws UnsupportedEncodingException {
        return getSubjectOrObjectPids(
                URLEncoder.encode("* <info:fedora/fedora-system:def/model#state> <info:fedora/fedora-system:def/model#Deleted>", "UTF-8"));
    }

    public List<RelationshipTuple> getSubjectOrObjectPids(String query) {
        List<RelationshipTuple> retval = new ArrayList<RelationshipTuple>();
//        String command =
//                accessProvider.getFedoraHost() + "/risearch?type=triples&lang=spo&format=N-Triples&query=" + query;

//        InputStream stream = null;
//        try {
//            stream =
//                    RESTHelper.get(command,
//                            USER, PASS,
//                            false);
//            if (stream == null) return null;
//            String result = IOUtils.readAsString(stream, Charset.forName("UTF-8"), true);
        WebResource resource = accessProvider.getFedoraWebResource("/risearch?type=triples&lang=spo&format=N-Triples&query="
                + query);
        String result = resource.get(String.class);
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
//        } catch (Exception e) {
//            LOGGER.error(e.getMessage(), e);
//        } finally {
//            if (stream != null) try {
//                stream.close();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
        return retval;
    }

    public DigitalObjectModel getModel(String uuid) throws IOException {
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

    public Document getRelsExt(String uuid) throws IOException {
//        String relsExtUrl = relsExtUrl(uuid);
        LOGGER.debug("Reading rels ext from " + accessProvider.getFedoraHost() + "/get/" + uuid + "/RELS-EXT");
        WebResource resource = accessProvider.getFedoraWebResource("/get/" + uuid + "/RELS-EXT");
        String docString = resource.accept(MediaType.APPLICATION_XML).get(String.class);
//        InputStream docStream =
//                RESTHelper.get(relsExtUrl,
//                        USER,
//                        PASS,
//                        true);
        if (docString == null) {
            throw new ConnectionException("Cannot get RELS EXT data.");
        }
        try {
            return XMLUtils.parseDocument(docString, true);
        } catch (ParserConfigurationException e) {
            LOGGER.error(e.getMessage(), e);
            throw new IOException(e);
        } catch (SAXException e) {
            LOGGER.error(e.getMessage(), e);
            throw new IOException(e);
        }
//        finally {
//            docStream.close();
//        }
    }

//    public String relsExtUrl(String uuid) {
//        String url = accessProvider.getFedoraHost() + "/get/" + uuid + "/RELS-EXT";
//        return url;
//    }

    public DigitalObjectModel getDigitalObjectModel(String uuid) throws IOException {
        return getDigitalObjectModel(getRelsExt(uuid));
    }

    public DigitalObjectModel getDigitalObjectModel(Document relsExt) {
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
    public String getFedoraDatastreamsList(String uuid) {
        String datastreamsListPath =
                accessProvider.getFedoraHost() + "/objects/" + uuid + "/datastreams?format=xml";
        return datastreamsListPath;
    }

    public InputStream getPdf(String uuid) throws IOException {
        ClientResponse response =
                accessProvider.getFedoraWebResource("/objects/" + uuid + "/datastreams/IMG_FULL/content")
                    .accept("application/pdf").get(ClientResponse.class);

        if (response.getStatus() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatus());
        }
        InputStream is = response.getEntityInputStream();
        return is;
    }

    public void purgeObject(String uuid) {
//        String fedoraObject = FEDORA_URL + "/objects/" + uuid;
//
//        Client client = Client.create();
//
//        WebResource webResource = client.resource(fedoraObject);
//
//        client.addFilter(new HTTPBasicAuthFilter(USER, PASS));

        ClientResponse response = accessProvider.getFedoraWebResource("/objects/" + uuid).delete(ClientResponse.class);

        if (response.getStatus() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatus());
        }
    }

    public String getOcr(String uuid) {
        String ocrUrl = ocr(uuid);
        LOGGER.debug("Reading OCR +" + ocrUrl);
        String ocrOutput = accessProvider.getFedoraWebResource("/objects/" + uuid + "/datastreams/TEXT_OCR/content").get(String.class);
//        InputStream docStream = null;
//        try {
//            docStream =
//                    RESTHelper.get(ocrUrl,
//                            USER,
//                            PASS,
//                            true);
//            if (docStream == null) return null;
//        } catch (IOException e) {
//            // ocr is not available
//            e.printStackTrace();
//            return null;
//        }
//        BufferedReader br = new BufferedReader(new InputStreamReader(docStream));
//        StringBuilder sb = new StringBuilder();
//        String line = null;
//        try {
//            while ((line = br.readLine()) != null) {
//                sb.append(line).append('\n');
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//            LOGGER.error("Reading ocr +" + ocrUrl, e);
//        } finally {
//            if (br != null) {
//                try {
//                    br.close();
//                } catch (IOException e) {
//                    LOGGER.error("Closing stream +" + ocrUrl, e);
//                    e.printStackTrace();
//                } finally {
//                    br = null;
//                }
//            }
//            try {
//                if (docStream != null) docStream.close();
//            } catch (IOException e) {
//                e.printStackTrace();
//            } finally {
//                docStream = null;
//            }
//        }
//        return sb.toString();
        return ocrOutput;
    }

    public boolean setOcr(String uuid, String ocr) throws CreateObjectException {
        return insertManagedDatastream(Constants.DATASTREAM_ID.TEXT_OCR, uuid, ocr, false, "text/plain");
    }

    public boolean setThumbnail(String uuid, String path) throws CreateObjectException {
        return insertManagedDatastream(Constants.DATASTREAM_ID.IMG_THUMB, uuid, path, true, "image/jpg");
    }

    public boolean setPreview(String uuid, String path) throws CreateObjectException {
        return insertManagedDatastream(Constants.DATASTREAM_ID.IMG_PREVIEW, uuid, path, true, "image/jpg");
    }

    /**
     * Ocr.
     *
     * @param uuid the uuid
     * @return the string
     */
    private String ocr(String uuid) {
        String fedoraObject =
                accessProvider.getFedoraHost() + "/objects/" + uuid + "/datastreams/TEXT_OCR/content";
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

        String query =
                "/objects/" + (uuid.contains("uuid:") ? uuid : "uuid:".concat(uuid)) + "/datastreams/" + dsId.getValue();

        MultivaluedMap queryParams = new MultivaluedMapImpl();
        queryParams.add("controlGroup", "M");
        queryParams.add("versionable", "true");
        queryParams.add("dsState", "A");
        queryParams.add("mimeType", mimeType);

//        boolean success;
//        String url = FEDORA_URL.concat(prepUrl);
        WebResource resource = accessProvider.getFedoraWebResource(query);
        ClientResponse response = null;
        try {
            if (isFile) {
                response = resource.queryParams(queryParams).post(ClientResponse.class, new File(filePathOrContent));
            } else {
                resource.queryParams(queryParams).post(ClientResponse.class, filePathOrContent);
            }

        } catch (UniformInterfaceException e) {
            int status = e.getResponse().getStatus();
            if (status == 404) {
                LOGGER.fatal("Process not found");
            }

            LOGGER.error(e.getMessage());
            e.printStackTrace();
            throw new CreateObjectException("Unable to post "
                    + (isFile ? ("a file: " + filePathOrContent + " as a ") : "")
                    + "managed datastream to the object: " + uuid);
        }

        if (response.getStatus() == 201) {
            LOGGER.info("An " + dsId.getValue() + (isFile ? (" file: " + filePathOrContent) : "")
                    + " has been inserted to the digital object: " + uuid + " as a " + dsId.getValue()
                    + " datastream.");

            return true;
        } else {
            LOGGER.error("An error occured during inserting an " + dsId.getValue()
                    + (isFile ? (" file: " + filePathOrContent) : "") + " to the digital object: " + uuid
                    + " as a " + dsId.getValue() + " datastream.");
            LOGGER.error("Error " + response.getStatus());
            return false;

        }
    }
}
