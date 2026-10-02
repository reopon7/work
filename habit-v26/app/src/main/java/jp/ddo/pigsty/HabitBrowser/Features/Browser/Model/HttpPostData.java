package jp.ddo.pigsty.HabitBrowser.Features.Browser.Model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.ddo.pigsty.HabitBrowser.Util.Is;

@SuppressWarnings({"rawtypes", "unchecked"})
public class HttpPostData {
    private String charset;
    private String encType;
    private Map query;
    private String url;

    public HttpPostData() {
        encType = "";
        query = new LinkedHashMap();
    }

    public void addQuery(String key, String value) {
        if (!getQuery().containsKey(key)) {
            getQuery().put(key, new ArrayList());
        }
        ((List) getQuery().get(key)).add(value);
    }

    public String getCharset() { return charset; }
    public String getEncType() { return encType; }

    public byte[] getPostByte(String boundary) {
        String charsetName = charset;
        if (Is.isBlank(charsetName)) {
            charsetName = "UTF-8";
        }

        StringBuilder builder = new StringBuilder();

        try {
            if ("multipart/form-data".equals(encType.toLowerCase())) {
                for (Object keyObject : query.keySet()) {
                    String key = (String) keyObject;
                    List values = (List) query.get(key);
                    for (Object valueObject : values) {
                        String value = (String) valueObject;
                        builder.append("--").append(boundary).append("\r\n");
                        builder.append("Content-Disposition: form-data; name=\"")
                                .append(key)
                                .append("\"\r\n\r\n")
                                .append(value)
                                .append("\r\n");
                    }
                }
                builder.append("\r\n--").append(boundary).append("--\r\n");
                return builder.toString().getBytes(charsetName);
            }

            for (Object keyObject : query.keySet()) {
                String key = (String) keyObject;
                List values = (List) query.get(key);
                for (Object valueObject : values) {
                    String value = (String) valueObject;
                    if (builder.length() > 0) {
                        builder.append('&');
                    }
                    builder.append(key).append('=').append(value);
                }
            }
            return builder.toString().getBytes(charsetName);
        } catch (Exception e) {
            return null;
        }
    }

    public Map getQuery() { return query; }
    public String getUrl() { return url; }
    public void setCharset(String charset) { this.charset = charset; }
    public void setEncType(String encType) { this.encType = encType; }
    public void setQuery(Map query) { this.query = query; }
    public void setUrl(String url) { this.url = url; }
}
