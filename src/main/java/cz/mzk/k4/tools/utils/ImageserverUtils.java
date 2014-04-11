package cz.mzk.k4.tools.utils;

import com.jcraft.jsch.*;
import cz.mzk.k4.tools.Configuration;
import org.apache.commons.io.FileUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author: Martin Rumanek
 * @version: 11/14/13
 */
public class ImageserverUtils {

    private Configuration configuration = new Configuration();
    public static final String IMAGE_SERVER_URL = "http://medit.mzk.cz/imageserver/";
    private static final String IMAGE_SERVER_PATH = "/imageserver-data/";

    /**
     * List filenames from workspace (mzk sysifos)
     *
     * @param path
     * @return
     * @throws JSchException
     * @throws SftpException
     * @throws java.io.IOException
     */
    public List<String> getFilenames(String path) throws JSchException, SftpException, IOException {

        ChannelSftp channelWorkspace = getSftpConnection(configuration.getSshUserWorkspace(),
                configuration.getSshHostWorkspace(), configuration.getPasswordWorkspace());


        ArrayList<ChannelSftp.LsEntry> list = new ArrayList<ChannelSftp.LsEntry>(channelWorkspace.ls(path));
        channelWorkspace.disconnect();


        List<String> filenames = new ArrayList<String>();

        for (ChannelSftp.LsEntry entry : list) {
            filenames.add(entry.getFilename());
        }
        filenames.remove(".");
        filenames.remove("..");

        return filenames;

    }

    public void uploadToImageserver(String path) throws JSchException, SftpException, IOException {
        List<String> list = getFilenames(path);

        ChannelSftp workspaceChannel = getSftpConnection(configuration.getSshUserWorkspace(),
                configuration.getSshHostWorkspace(), null);

        for (String item : list) {
            BufferedWriter writer = null;
            File originalImage = File.createTempFile("rajhrad_tool", "image");
            File convertedImage = File.createTempFile("rajhrad_tool", ".jp2");
            FileUtils.copyInputStreamToFile(workspaceChannel.get(path + "/" + item), originalImage);


            List<String> command = new ArrayList<String>();
            command.add("/home/rumanekm/.meditor/bin/compress.sh");
            command.add("/home/rumanekm/.meditor/djatoka/");
            command.add(originalImage.getAbsolutePath());
            command.add(convertedImage.getAbsolutePath());
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.start();


            originalImage.delete();
            convertedImage.delete();

        }
        workspaceChannel.disconnect();

    }

    /**
     * Method uploading image input stream to image server. Name of uploaded image on image
     * server is its uuid without the "uuid:" part
     * Connection settings are stored in rajhard.properties file
     * Path on imageserver is stored in constant IMAGE_SERVER_PATH
     *
     * @param jp2InputStream Image stream to be uploaded
     * @param uuid           Uuid of image
     * @throws JSchException If the connection to image server was unsuccessful
     * @throws SftpException If the upload to image server was unsuccessful
     */
    public void uploadJp2ToImageserver(InputStream jp2InputStream, String uuid) throws JSchException, SftpException {
        try {
            //Connect to image server
            ChannelSftp imgServerChannel = getSftpConnection(configuration.getImageServerUserWorkspace(),
                    configuration.getImageServerHostWorkspace(), configuration.getPrivateKeypassphrase());
            //Upload File
            imgServerChannel.put(jp2InputStream, IMAGE_SERVER_PATH + uuid.substring("uuid:".length()) + ".jp2");
            //Disconnect from server
            imgServerChannel.disconnect();
        } catch (JSchException e) {
            throw new JSchException("Chyba připojení se k imageserveru: " + e.getMessage());
        } catch (SftpException e) {
            throw new SftpException(e.id, "Chyba uploadu na imageserver: " + e.getMessage());
        }
    }

    private ChannelSftp getSftpConnection(String user, String host, String password) throws JSchException {
        JSch jsch = new JSch();

        Session session;
        if (configuration.getPasswordWorkspace() == null) {
            jsch.addIdentity(configuration.getPathPrivateKey(), configuration.getPrivateKeypassphrase());
            session = jsch.getSession(user, host, 22);
        } else {
            session = jsch.getSession(user, host, 22);
            session.setPassword(password);
        }

        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();
        Channel channel = session.openChannel("sftp");
        channel.connect();
        ChannelSftp channelSftp = (ChannelSftp) channel;
        return channelSftp;
    }
}
