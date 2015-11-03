/*
 * Copyright (c) 2013 Rising Oak LLC.
 *
 * Distributed under the MIT license: http://opensource.org/licenses/MIT
 */

package com.offbytwo.jenkins.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offbytwo.jenkins.client.util.RequestReleasingInputStream;
import com.offbytwo.jenkins.client.validator.HttpResponseValidator;
import com.offbytwo.jenkins.model.BaseModel;
import com.offbytwo.jenkins.model.Crumb;
import com.offbytwo.jenkins.model.ExtractHeader;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.apache.commons.lang.StringUtils.isNotBlank;

//import com.offbytwo.jenkins.client.util.HttpResponseContentExtractor;

public class JenkinsHttpClient {

    private static final int SO_TIMEOUT_IN_MILLISECONDS = 3000;
    private static final int CONNECTION_TIMEOUT_IN_MILLISECONDS = 500;

    private URI uri;
    private CloseableHttpClient client;
    private BasicHttpContext localContext;
    private HttpResponseValidator httpResponseValidator;
//    private HttpResponseContentExtractor contentExtractor;

    private ObjectMapper mapper;
    private String context;

    /**
     * Create an unauthenticated Jenkins HTTP client
     *
     * @param uri    Location of the jenkins server (ex. http://localhost:8080)
     * @param client Configured CloseableHttpClient to be used
     */
    public JenkinsHttpClient(URI uri, CloseableHttpClient client) {
        this.context = uri.getPath();
        if (!context.endsWith("/")) {
            context += "/";
        }
        this.uri = uri;
        this.mapper = getDefaultMapper();
        this.client = client;
        this.httpResponseValidator = new HttpResponseValidator();
//        this.contentExtractor = new HttpResponseContentExtractor();
    }

    /**
     * Create an unauthenticated Jenkins HTTP client
     *
     * @param uri     Location of the jenkins server (ex. http://localhost:8080)
     * @param builder Configured HttpClientBuilder to be used
     */
    public JenkinsHttpClient(URI uri, HttpClientBuilder builder) {
        this(uri, builder.build());
    }

    /**
     * Create an unauthenticated Jenkins HTTP client
     *
     * @param uri Location of the jenkins server (ex. http://localhost:8080)
     */
    public JenkinsHttpClient(URI uri) {
        this(uri, HttpClientBuilder.create());
        this.context = uri.getPath();
 
      if (!context.endsWith("/")) {
          context += "/";
      }
      this.uri = uri;
      this.mapper = getDefaultMapper();
      
      HttpParams httpParams = new BasicHttpParams();
      httpParams.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, SO_TIMEOUT_IN_MILLISECONDS);
      httpParams.setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, CONNECTION_TIMEOUT_IN_MILLISECONDS);
     

      this.httpResponseValidator = new HttpResponseValidator();
    }

    /**
     * Create an authenticated Jenkins HTTP client
     *
     * @param uri      Location of the jenkins server (ex. http://localhost:8080)
     * @param username Username to use when connecting
     * @param password Password or auth token to use when connecting
     */
    public JenkinsHttpClient(URI uri, String username, String password) {
        this(uri, addAuthentication(HttpClientBuilder.create(), uri, username, password));
        if (isNotBlank(username)) {
            localContext = new BasicHttpContext();
            localContext.setAttribute("preemptive-auth", new BasicScheme());
        }
    }

    public <T extends BaseModel> T  getQuietly(String path, Class<T> cls) {
        T value;
        try {
            value = get(path, cls);
        } catch (IOException e) {
            return null;
        }
        return value;
    }

    /**
     * Perform a GET request and parse the response to the given class
     *
     * @param path path to request, can be relative or absolute
     * @param cls  class of the response
     * @param <T>  type of the response
     * @return an instance of the supplied class
     * @throws IOException, HttpResponseException
     */
    public <T extends BaseModel> T get(String path, Class<T> cls) throws IOException {
        HttpGet getMethod = new HttpGet(api(path));
        HttpResponse response = client.execute(getMethod, localContext);
        try {
            httpResponseValidator.validateResponse(response);
            return objectFromResponse(cls, response);
        } finally {
            EntityUtils.consume(response.getEntity());
            releaseConnection(getMethod);
        }
    }

    /**
     * Perform a GET request and parse the response and return a simple string of the content
     *
     * @param path path to request, can be relative or absolute
     * @return the entity text
     * @throws IOException, HttpResponseException
     */
    public String get(String path) throws IOException {
        HttpGet getMethod = new HttpGet(api(path));
        HttpResponse response = client.execute(getMethod, localContext);

        try {
            httpResponseValidator.validateResponse(response);
            return IOUtils.toString(response.getEntity().getContent());
        } finally {
            EntityUtils.consume(response.getEntity());
            releaseConnection(getMethod);
        }
    }

    /**
     * Perform a GET request and return the response as InputStream
     *
     * @param path path to request, can be relative or absolute
     * @return the response stream
     * @throws IOException, HttpResponseException
     */
    public InputStream getFile(URI path) throws IOException {
        HttpGet getMethod = new HttpGet(path);
        HttpResponse response = client.execute(getMethod, localContext);
        httpResponseValidator.validateResponse(response);
        return new RequestReleasingInputStream(response.getEntity().getContent(), getMethod);
    }

    public <R extends BaseModel, D> R post(String path, D data, Class<R> cls) throws IOException {
        return post(path, data, cls, true);
    }

    /**
     * Perform a POST request and parse the response to the given class
     *
     * @param path path to request, can be relative or absolute
     * @param data data to post
     * @param cls  class of the response
     * @param <R>  type of the response
     * @param <D>  type of the data
     * @return an instance of the supplied class
     * @throws IOException, HttpResponseException
     */
    public <R extends BaseModel, D> R post(String path, D data, Class<R> cls, boolean crumbFlag) throws IOException {
        HttpPost request = new HttpPost(api(path));
        if (crumbFlag == true) {
            Crumb crumb = getQuietly("/crumbIssuer", Crumb.class);
            if (crumb != null) {
                request.addHeader(new BasicHeader(crumb.getCrumbRequestField(), crumb.getCrumb()));
            }
        }

        if (data != null) {
            StringEntity stringEntity = new StringEntity(mapper.writeValueAsString(data), "application/json");
            request.setEntity(stringEntity);
        }
        HttpResponse response = client.execute(request, localContext);

        try {
            httpResponseValidator.validateResponse(response);

            if (cls != null) {
                R responseObject; 
                if(cls.equals(ExtractHeader.class) ) {
                  ExtractHeader location = new ExtractHeader();
                  location.setLocation(response.getFirstHeader("Location").getValue());
                  responseObject = (R) location;
                } else {
                  responseObject = objectFromResponse(cls, response);
                }
                return responseObject;
            } else {
                return null;
            }
        } finally {
            EntityUtils.consume(response.getEntity());
            releaseConnection(request);
        }
    }

    /**
     * Perform a POST request of XML (instead of using json mapper) and return a string rendering of the response
     * entity.
     *
     * @param path     path to request, can be relative or absolute
     * @param xml_data data data to post
     * @return A string containing the xml response (if present)
     * @throws IOException, HttpResponseException
     */
    public String post_xml(String path, String xml_data) throws IOException {
        return post_xml(path, xml_data, true);
    }

    public String post_xml(String path, String xml_data, boolean crumbFlag) throws IOException {
        HttpPost request = new HttpPost(api(path));
        if (crumbFlag == true) {
            Crumb crumb = getQuietly("/crumbIssuer", Crumb.class);
            if (crumb != null) {
                request.addHeader(new BasicHeader(crumb.getCrumbRequestField(), crumb.getCrumb()));
            }
        }

        if (xml_data != null) {
            request.setEntity(new StringEntity(xml_data, ContentType.create("text/xml", "utf-8")));
        }
        HttpResponse response = client.execute(request, localContext);
        httpResponseValidator.validateResponse(response);
        try {
            return IOUtils.toString(response.getEntity().getContent());
        } finally {
            EntityUtils.consume(response.getEntity());
            releaseConnection(request);
        }
    }

    /**
     * Post a text entity to the given URL using the default content type
     *
     * @param path
     * @param textData
     * @param crumbFlag
     * @return resulting response
     * @throws IOException
     */
    public String post_text(String path, String textData, boolean crumbFlag) throws IOException {
        return post_text(path, textData, ContentType.DEFAULT_TEXT, crumbFlag);
    }

    /**
     * Post a text entity to the given URL with the given content type
     *
     * @param path
     * @param textData
     * @param crumbFlag
     * @return resulting response
     * @throws IOException
     */
    public String post_text(String path, String textData, ContentType contentType, boolean crumbFlag)
        throws IOException {
        HttpPost request = new HttpPost(api(path));
        if (crumbFlag == true) {
            Crumb crumb = get("/crumbIssuer", Crumb.class);
            if (crumb != null) {
                request.addHeader(new BasicHeader(crumb.getCrumbRequestField(), crumb.getCrumb()));
            }
        }

        if (textData != null) {
            request.setEntity(new StringEntity(textData, contentType));
        }
        HttpResponse response = client.execute(request, localContext);
        httpResponseValidator.validateResponse(response);
        try {
            return IOUtils.toString(response.getEntity().getContent());
        } finally {
            EntityUtils.consume(response.getEntity());
            releaseConnection(request);
        }
    }

    /**
     * Perform POST request that takes no parameters and returns no response
     *
     * @param path path to request
     * @throws IOException, HttpResponseException
     */
    public void post(String path) throws IOException {
        post(path, null, null, true);
    }

    public void post(String path, boolean crumbFlag) throws IOException {
        post(path, null, null, crumbFlag);
    }

    private String urlJoin(String path1, String path2) {
        if (!path1.endsWith("/")) {
            path1 += "/";
        }
        if (path2.startsWith("/")) {
            path2 = path2.substring(1);
        }
        return path1 + path2;
    }

    private URI api(String path) {
        if (!path.toLowerCase().matches("https?://.*")) {
            path = urlJoin(this.context, path);
        }
        if (!path.contains("?")) {
            path = urlJoin(path, "api/json");
        } else {
            String[] components = path.split("\\?", 2);
            path = urlJoin(components[0], "api/json") + "?" + components[1];
        }
        return uri.resolve("/").resolve(path);
    }

    private <T extends BaseModel> T objectFromResponse(Class<T> cls, HttpResponse response) throws IOException {
        InputStream content = response.getEntity().getContent();
        T result = mapper.readValue(content, cls);
        result.setClient(this);
        return result;
    }

    private ObjectMapper getDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private void releaseConnection(HttpRequestBase httpRequestBase) {
        httpRequestBase.releaseConnection();
    }

    private static HttpClientBuilder addAuthentication(
            HttpClientBuilder builder, URI uri, String username, String password) {
        if (isNotBlank(username)) {
            CredentialsProvider provider = new BasicCredentialsProvider();
            AuthScope scope = new AuthScope(uri.getHost(), uri.getPort(), "realm");
            UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(username, password);
            provider.setCredentials(scope, credentials);
            builder.setDefaultCredentialsProvider(provider);

            builder.addInterceptorFirst(new PreemptiveAuth());
        }
        return builder;
    }
}
