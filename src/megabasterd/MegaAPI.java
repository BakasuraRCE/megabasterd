package megabasterd;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.util.logging.Logger.getLogger;
import javax.crypto.Cipher;
import static megabasterd.MiscTools.Bin2UrlBASE64;
import static megabasterd.MiscTools.UrlBASE642Bin;
import static megabasterd.MiscTools.bin2i32a;
import static megabasterd.MiscTools.cleanFilename;
import static megabasterd.MiscTools.findFirstRegex;
import static megabasterd.MiscTools.genID;
import static megabasterd.MiscTools.genRandomByteArray;
import static megabasterd.MiscTools.getWaitTimeExpBackOff;
import static megabasterd.MiscTools.i32a2bin;
import static megabasterd.MiscTools.mpi2big;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.codehaus.jackson.map.ObjectMapper;

public final class MegaAPI {

    public static final String API_URL = "https://g.api.mega.co.nz";
    public static final String API_KEY = null;
    public static final int REQ_ID_LENGTH = 10;

    public static int checkMEGAError(String data) {
        String error = findFirstRegex("^\\[?(\\-[0-9]+)\\]?$", data, 1);

        return error != null ? Integer.parseInt(error) : 0;
    }

    private long _seqno;

    private String _sid;

    private int[] _master_key;

    private BigInteger[] _rsa_priv_key;

    private int[] _password_aes;

    private String _user_hash;

    private String _root_id;

    private String _inbox_id;

    private String _email;

    private String _trashbin_id;

    private String _req_id;

    public MegaAPI() {
        _req_id = null;
        _trashbin_id = null;
        _email = null;
        _inbox_id = null;
        _root_id = null;
        _user_hash = null;
        _password_aes = null;
        _rsa_priv_key = null;
        _master_key = null;
        _sid = null;
        _req_id = genID(REQ_ID_LENGTH);

        Random randomno = new Random();

        _seqno = randomno.nextLong() & 0xffffffffL;

    }

    public String getEmail() {
        return _email;
    }

    public int[] getPassword_aes() {
        return _password_aes;
    }

    public String getUser_hash() {
        return _user_hash;
    }

    public String getSid() {
        return _sid;
    }

    public int[] getMaster_key() {
        return _master_key;
    }

    public BigInteger[] getRsa_priv_key() {
        return _rsa_priv_key;
    }

    public String getRoot_id() {
        return _root_id;
    }

    public String getInbox_id() {
        return _inbox_id;
    }

    public String getTrashbin_id() {
        return _trashbin_id;
    }

    private void _realLogin() throws Exception, MegaAPIException {

        String request = "[{\"a\":\"us\", \"user\":\"" + _email + "\", \"uh\":\"" + _user_hash + "\"}]";

        URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + (API_KEY != null ? "&ak=" + API_KEY : ""));

        String res = _rawRequest(request, url_api);

        ObjectMapper objectMapper = new ObjectMapper();

        HashMap[] res_map = objectMapper.readValue(res, HashMap[].class);

        String k = (String) res_map[0].get("k");

        String privk = (String) res_map[0].get("privk");

        _master_key = bin2i32a(decryptKey(UrlBASE642Bin(k), i32a2bin(_password_aes)));

        String csid = (String) res_map[0].get("csid");

        if (csid != null) {

            int[] enc_rsa_priv_key = bin2i32a(UrlBASE642Bin(privk));

            byte[] privk_byte = decryptKey(i32a2bin(enc_rsa_priv_key), i32a2bin(_master_key));

            _rsa_priv_key = _extractRSAPrivKey(privk_byte);

            byte[] raw_sid = CryptTools.rsaDecrypt(mpi2big(UrlBASE642Bin(csid)), _rsa_priv_key[0], _rsa_priv_key[1], _rsa_priv_key[2]);

            _sid = Bin2UrlBASE64(Arrays.copyOfRange(raw_sid, 0, 43));
        }

        fetchNodes();
    }

    public void login(String email, String password) throws Exception, MegaAPIException {

        _email = email;

        _password_aes = CryptTools.MEGAPrepareMasterKey(bin2i32a(password.getBytes()));

        _user_hash = CryptTools.MEGAUserHash(email.toLowerCase().getBytes(), _password_aes);

        _realLogin();
    }

    public void fastLogin(String email, int[] password_aes, String user_hash) throws Exception, MegaAPIException {

        _email = email;

        _password_aes = password_aes;

        _user_hash = user_hash;

        _realLogin();
    }

    public Long[] getQuota() {

        Long[] quota = null;

        try {
            String request = "[{\"a\": \"uq\", \"xfer\": 1, \"strg\": 1}]";

            URL url_api;

            url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String res = _rawRequest(request, url_api);

            ObjectMapper objectMapper = new ObjectMapper();

            HashMap[] res_map = objectMapper.readValue(res, HashMap[].class);

            quota = new Long[2];

            if (res_map[0].get("cstrg") instanceof Integer) {

                quota[0] = ((Number) res_map[0].get("cstrg")).longValue();

            } else if (res_map[0].get("cstrg") instanceof Long) {

                quota[0] = (Long) res_map[0].get("cstrg");
            }

            if (res_map[0].get("mstrg") instanceof Integer) {

                quota[1] = ((Number) res_map[0].get("mstrg")).longValue();

            } else if (res_map[0].get("mstrg") instanceof Long) {

                quota[1] = (Long) res_map[0].get("mstrg");
            }

        } catch (Exception ex) {

            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return quota;
    }

    public void fetchNodes() throws IOException {

        String request = "[{\"a\":\"f\", \"c\":1}]";

        URL url_api;

        try {

            url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String res = _rawRequest(request, url_api);

            System.out.println(res);

            ObjectMapper objectMapper = new ObjectMapper();

            HashMap[] res_map = objectMapper.readValue(res, HashMap[].class);

            for (Object o : (Iterable<? extends Object>) res_map[0].get("f")) {

                HashMap element = (HashMap<String, Object>) o;

                int file_type = (int) element.get("t");

                switch (file_type) {

                    case 2:
                        _root_id = (String) element.get("h");
                        break;
                    case 3:
                        _inbox_id = (String) element.get("h");
                        break;
                    case 4:
                        _trashbin_id = (String) element.get("h");
                        break;
                    default:
                        break;
                }
            }

        } catch (IOException | MegaAPIException ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    private String _rawRequest(String request, URL url_api) throws IOException, MegaAPIException {

        String response = null;

        try (CloseableHttpClient httpclient = MiscTools.getApacheKissHttpClient()) {

            boolean error;

            int conta_error = 0;

            HttpPost httppost;

            do {
                error = true;

                try {

                    httppost = new HttpPost(url_api.toURI());

                    httppost.setHeader("Content-type", "application/json");

                    httppost.setEntity(new StringEntity(request));

                    try (CloseableHttpResponse httpresponse = httpclient.execute(httppost)) {

                        if (httpresponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                            System.out.println("Failed : HTTP error code : " + httpresponse.getStatusLine().getStatusCode());

                        } else {

                            InputStream is = httpresponse.getEntity().getContent();

                            try (ByteArrayOutputStream byte_res = new ByteArrayOutputStream()) {

                                byte[] buffer = new byte[16 * 1024];

                                int reads;

                                while ((reads = is.read(buffer)) != -1) {

                                    byte_res.write(buffer, 0, reads);
                                }

                                response = new String(byte_res.toByteArray());

                                if (response.length() > 0) {

                                    if (checkMEGAError(response) == 0) {
                                        error = false;
                                    }
                                }

                            }
                        }

                    }

                } catch (IOException | URISyntaxException ex) {
                    Logger.getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
                }

                if (error) {
                    
                    System.out.println("MegaAPI ERROR. Waiting for retry...");
                    
                    try {
                        Thread.sleep(getWaitTimeExpBackOff(conta_error++)*1000);
                    } catch (InterruptedException ex) {
                        Logger.getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
                    }

                } else {

                    conta_error = 0;
                }

            } while (error);

        }

        _seqno++;

        return response;

    }

    public String getMegaFileDownloadUrl(String link) throws IOException, MegaAPIException {

        String file_id = findFirstRegex("#.*?!([^!]+)", link, 1);

        String request;

        URL url_api;

        if (findFirstRegex("#N!", link, 0) != null) {
            String folder_id = findFirstRegex("###n=(.+)$", link, 1);

            request = "[{\"a\":\"g\", \"g\":\"1\", \"n\":\"" + file_id + "\"}]";

            url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + (API_KEY != null ? "&ak=" + API_KEY : "") + "&n=" + folder_id);

        } else {

            request = "[{\"a\":\"g\", \"g\":\"1\", \"p\":\"" + file_id + "\"}]";
            url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + (API_KEY != null ? "&ak=" + API_KEY : ""));
        }

        String data = _rawRequest(request, url_api);

        ObjectMapper objectMapper = new ObjectMapper();

        HashMap[] res_map = objectMapper.readValue(data, HashMap[].class);

        return (String) res_map[0].get("g");
    }

    public String[] getMegaFileMetadata(String link) throws MegaAPIException, MalformedURLException, IOException {

        String file_id = findFirstRegex("#.*?!([^!]+)", link, 1);

        String file_key = findFirstRegex("#.*?![^!]+!([^!#]+)", link, 1);

        String request;

        URL url_api;

        if (findFirstRegex("#N!", link, 0) != null) {
            String folder_id = findFirstRegex("###n=(.+)$", link, 1);

            request = "[{\"a\":\"g\", \"g\":\"1\", \"n\":\"" + file_id + "\"}]";

            url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + (API_KEY != null ? "&ak=" + API_KEY : "") + "&n=" + folder_id);

        } else {

            request = "[{\"a\":\"g\", \"p\":\"" + file_id + "\"}]";

            url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + (API_KEY != null ? "&ak=" + API_KEY : ""));
        }

        String data = _rawRequest(request, url_api);

        ObjectMapper objectMapper = new ObjectMapper();

        HashMap[] res_map = objectMapper.readValue(data, HashMap[].class);

        String fsize = String.valueOf(res_map[0].get("s"));

        String at = (String) res_map[0].get("at");

        String[] file_data = null;

        HashMap att_map = _decAttr(at, CryptTools.initMEGALinkKey(file_key));

        if (att_map != null) {

            String fname = cleanFilename((String) att_map.get("n"));

            file_data = new String[]{fname, fsize, file_key};

        } else {

            throw new MegaAPIException("-14");
        }

        return file_data;
    }

    private byte[] _encAttr(String attr, byte[] key) {

        byte[] attr_byte = ("MEGA" + attr).getBytes();

        int l = (int) (16 * Math.ceil((double) attr_byte.length / 16));

        byte[] new_attr_byte = Arrays.copyOfRange(attr_byte, 0, l);

        byte[] ret = null;

        try {

            ret = CryptTools.aes_cbc_encrypt(new_attr_byte, key, CryptTools.AES_ZERO_IV);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return ret;
    }

    private HashMap _decAttr(String encAttr, byte[] key) {

        HashMap res_map = null;

        byte[] decrypted_at = null;

        try {

            Cipher decrypter = CryptTools.genDecrypter("AES", "AES/CBC/NoPadding", key, CryptTools.AES_ZERO_IV);

            decrypted_at = decrypter.doFinal(UrlBASE642Bin(encAttr));

            String att = new String(decrypted_at).replaceAll("[\0]+$", "").replaceAll("^MEGA", "");

            ObjectMapper objectMapper = new ObjectMapper();

            res_map = objectMapper.readValue(att, HashMap.class);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);

        }

        return res_map;
    }

    public String initUploadFile(String filename) {

        String ul_url = null;

        try {

            File f = new File(filename);

            String request = "[{\"a\":\"u\", \"s\":" + String.valueOf(f.length()) + "}]";

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String res = _rawRequest(request, url_api);

            ObjectMapper objectMapper = new ObjectMapper();

            HashMap[] res_map = objectMapper.readValue(res, HashMap[].class);

            ul_url = (String) res_map[0].get("p");

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return ul_url;
    }

    public HashMap<String, Object> finishUploadFile(String fbasename, int[] ul_key, int[] fkey, int[] meta_mac, String completion_handle, String mega_parent, byte[] master_key, String root_node, byte[] share_key) {

        HashMap[] res_map = null;

        try {

            byte[] enc_att = _encAttr("{\"n\":\"" + fbasename + "\"}", i32a2bin(Arrays.copyOfRange(ul_key, 0, 4)));

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String request = "[{\"a\":\"p\", \"t\":\"" + mega_parent + "\", \"n\":[{\"h\":\"" + completion_handle + "\", \"t\":0, \"a\":\"" + Bin2UrlBASE64(enc_att) + "\", \"k\":\"" + Bin2UrlBASE64(encryptKey(i32a2bin(fkey), master_key)) + "\"}], \"i\":\"" + _req_id + "\", \"cr\" : [ [\"" + root_node + "\"] , [\"" + completion_handle + "\"] , [0,0, \"" + Bin2UrlBASE64(encryptKey(i32a2bin(fkey), share_key)) + "\"]]}]";

            String res = _rawRequest(request, url_api);

            ObjectMapper objectMapper = new ObjectMapper();

            res_map = objectMapper.readValue(res, HashMap[].class);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return res_map[0];
    }

    public byte[] encryptKey(byte[] a, byte[] key) throws Exception {

        return CryptTools.aes_ecb_encrypt(a, key);
    }

    public byte[] decryptKey(byte[] a, byte[] key) throws Exception {

        return CryptTools.aes_ecb_decrypt(a, key);
    }

    private BigInteger[] _extractRSAPrivKey(byte[] rsa_data) {

        BigInteger[] rsa_key = new BigInteger[4];

        for (int i = 0, offset = 0; i < 4; i++) {

            int l = ((256 * ((((int) rsa_data[offset]) & 0xFF)) + (((int) rsa_data[offset + 1]) & 0xFF) + 7) / 8) + 2;

            rsa_key[i] = mpi2big(Arrays.copyOfRange(rsa_data, offset, offset + l));

            offset += l;
        }

        return rsa_key;
    }

    public HashMap<String, Object> createDir(String name, String parent_node, byte[] node_key, byte[] master_key) {

        HashMap[] res_map = null;

        try {

            byte[] enc_att = _encAttr("{\"n\":\"" + name + "\"}", node_key);

            byte[] enc_node_key = encryptKey(node_key, master_key);

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String request = "[{\"a\":\"p\", \"t\":\"" + parent_node + "\", \"n\":[{\"h\":\"xxxxxxxx\",\"t\":1,\"a\":\"" + Bin2UrlBASE64(enc_att) + "\",\"k\":\"" + Bin2UrlBASE64(enc_node_key) + "\"}],\"i\":\"" + _req_id + "\"}]";

            String res = _rawRequest(request, url_api);

            System.out.println(res);

            ObjectMapper objectMapper = new ObjectMapper();

            res_map = objectMapper.readValue(res, HashMap[].class);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return res_map[0];

    }

    public HashMap<String, Object> createDirInsideAnotherSharedDir(String name, String parent_node, byte[] node_key, byte[] master_key, String root_node, byte[] share_key) {

        HashMap[] res_map = null;

        try {

            byte[] enc_att = _encAttr("{\"n\":\"" + name + "\"}", node_key);

            byte[] enc_node_key = encryptKey(node_key, master_key);

            byte[] enc_node_key_s = encryptKey(node_key, share_key);

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String request = "[{\"a\":\"p\", \"t\":\"" + parent_node + "\", \"n\":[{\"h\":\"xxxxxxxx\",\"t\":1,\"a\":\"" + Bin2UrlBASE64(enc_att) + "\",\"k\":\"" + Bin2UrlBASE64(enc_node_key) + "\"}],\"i\":\"" + _req_id + "\", \"cr\" : [ [\"" + root_node + "\"] , [\"xxxxxxxx\"] , [0,0, \"" + Bin2UrlBASE64(enc_node_key_s) + "\"]]}]";

            String res = _rawRequest(request, url_api);

            System.out.println(res);

            ObjectMapper objectMapper = new ObjectMapper();

            res_map = objectMapper.readValue(res, HashMap[].class);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return res_map[0];

    }

    public String getPublicFileLink(String node, byte[] node_key) {

        String public_link = null;

        try {

            String file_id;

            List res_map;

            String request = "[{\"a\":\"l\", \"n\":\"" + node + "\"}]";

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String res = _rawRequest(request, url_api);

            System.out.println(res);

            ObjectMapper objectMapper = new ObjectMapper();

            res_map = objectMapper.readValue(res, List.class);

            file_id = (String) res_map.get(0);

            public_link = "https://mega.nz/#!" + file_id + "!" + Bin2UrlBASE64(node_key);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return public_link;
    }

    public String getPublicFolderLink(String node, byte[] node_key) {

        String public_link = null;

        try {

            String folder_id;

            List res_map;

            String request = "[{\"a\":\"l\", \"n\":\"" + node + "\", \"i\":\"" + _req_id + "\"}]";

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String res = _rawRequest(request, url_api);

            System.out.println(res);

            ObjectMapper objectMapper = new ObjectMapper();

            res_map = objectMapper.readValue(res, List.class);

            folder_id = (String) res_map.get(0);

            public_link = "https://mega.nz/#F!" + folder_id + "!" + Bin2UrlBASE64(node_key);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return public_link;
    }

    public int[] genUploadKey() {

        return bin2i32a(genRandomByteArray(24));
    }

    public byte[] genFolderKey() {

        return genRandomByteArray(16);
    }

    public byte[] genShareKey() {

        return genRandomByteArray(16);
    }

    public void shareFolder(String node, byte[] node_key, byte[] share_key) {

        try {

            String ok = Bin2UrlBASE64(encryptKey(share_key, i32a2bin(getMaster_key())));

            String enc_nk = Bin2UrlBASE64(encryptKey(node_key, share_key));

            String ha = cryptoHandleauth(node);

            String request = "[{\"a\":\"s2\",\"n\":\"" + node + "\",\"s\":[{\"u\":\"EXP\",\"r\":0}],\"i\":\"" + _req_id + "\",\"ok\":\"" + ok + "\",\"ha\":\"" + ha + "\",\"cr\":[[\"" + node + "\"],[\"" + node + "\"],[0,0,\"" + enc_nk + "\"]]}]";

            System.out.println(request);

            URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + "&sid=" + _sid + (API_KEY != null ? "&ak=" + API_KEY : ""));

            String res = _rawRequest(request, url_api);

            System.out.println(res);

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public String cryptoHandleauth(String h) {

        String ch = null;

        try {

            ch = Bin2UrlBASE64(encryptKey((h + h).getBytes(), i32a2bin(getMaster_key())));

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return ch;
    }

    public HashMap<String, Object> getFolderNodes(String folder_id, String folder_key) throws Exception {

        HashMap<String, Object> folder_nodes;

        String request = "[{\"a\":\"f\", \"c\":\"1\", \"r\":\"1\"}]";

        URL url_api = new URL(API_URL + "/cs?id=" + String.valueOf(_seqno) + (API_KEY != null ? "&ak=" + API_KEY : "") + "&n=" + folder_id);

        String res = _rawRequest(request, url_api);

        System.out.println(res);

        ObjectMapper objectMapper = new ObjectMapper();

        HashMap[] res_map = objectMapper.readValue(res, HashMap[].class);

        folder_nodes = new HashMap<>();

        for (Object o : (Iterable<? extends Object>) res_map[0].get("f")) {

            HashMap<String, Object> node = (HashMap<String, Object>) o;

            String[] node_k = ((String) node.get("k")).split(":");

            String dec_node_k = Bin2UrlBASE64(decryptKey(UrlBASE642Bin(node_k[1]), _urlBase64KeyDecode(folder_key)));

            HashMap at = _decAttr((String) node.get("a"), _urlBase64KeyDecode(dec_node_k));

            HashMap<String, Object> the_node = new HashMap<>();

            the_node.put("type", node.get("t"));

            the_node.put("parent", node.get("p"));

            the_node.put("key", dec_node_k);

            if (node.get("s") != null) {

                if (node.get("s") instanceof Integer) {

                    long size = ((Number) node.get("s")).longValue();
                    the_node.put("size", size);

                } else if (node.get("s") instanceof Long) {

                    long size = (Long) node.get("s");
                    the_node.put("size", size);
                }
            }

            the_node.put("name", at.get("n"));

            the_node.put("h", node.get("h"));

            folder_nodes.put((String) node.get("h"), the_node);
        }

        return folder_nodes;
    }

    private byte[] _urlBase64KeyDecode(String key) {

        try {
            byte[] key_bin = UrlBASE642Bin(key);

            if (key_bin.length < 32) {

                return Arrays.copyOfRange(key_bin, 0, 16);

            } else {

                int[] key_i32a = bin2i32a(Arrays.copyOfRange(key_bin, 0, 32));

                int[] k = {key_i32a[0] ^ key_i32a[4], key_i32a[1] ^ key_i32a[5], key_i32a[2] ^ key_i32a[6], key_i32a[3] ^ key_i32a[7]};

                return i32a2bin(k);
            }

        } catch (Exception ex) {
            getLogger(MegaAPI.class.getName()).log(Level.SEVERE, null, ex);
        }

        return null;
    }

}
