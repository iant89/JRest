package jrest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

public abstract class RestServer {
	private static ServerSocket server;	
	
	private final static Gson gson;

	private final Map<String, Map<HttpMethod, EndPointWrapper>> endpointMap = new HashMap<>();
	
	static {
		gson = new GsonBuilder().serializeNulls().create();
	}
	
	public RestServer() {
		
		new Thread(()-> {
			try {
				server = new ServerSocket(getPort());
				server.setSoTimeout(1000);
				System.out.println("Server started on: " + server.getLocalPort());
				while(true) {

					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}

					final Socket incoming;
					try {
						incoming = server.accept();
						incoming.setKeepAlive(true);

						new Thread(new Runnable() {
							public void run() {
								try {
									while(true) {
										if ( incoming.isClosed() )
											break;

										// Parse
										HttpRequest<?> request = parseRequest(incoming.getInetAddress().getHostAddress(), incoming.getPort(), incoming.getInputStream());
										if ( request == null )
											continue;
										
										// Handle request logic
										onRequest(incoming, request);

							        	// Dont burn CPU
										Thread.sleep(1);
									}
								} catch(Exception e) {
									e.printStackTrace();
								}
							}
						}).start();
					} catch(SocketTimeoutException e) {
						// No log
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} catch (IOException e1) {
				System.out.println("Error making server... " + e1);
				e1.printStackTrace();
			} finally {
				try {
					server.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}).start();
	}
	
	@SuppressWarnings("unchecked")
	protected <T> void onRequest(Socket socket, HttpRequest<?> request) throws UnsupportedEncodingException, IOException {
		// Log
		if ( request != null )
			System.out.println("["+new SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis()) + "] Incoming request: " + request);
		
		// Get matching endpoint
		EndPointWrapper<T> endpoint = getEndPoint(request.getURI().getPath(), request.getMethod());
		if ( endpoint != null ) {
			ResponseEntity<T> response = endpoint.query((HttpRequest<T>) request);
			if ( response == null )
				response = new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
			
			Object body = response.getBody();
			if ( body == null )
				body = new String();
			
			// Convert body
			String writeBody = null;
			if ( body instanceof JsonElement ) {
				writeBody = gson.toJson(body);
			} else {
				writeBody = body.toString();
			}
			
			// Write response
			BufferedOutputStream b = new BufferedOutputStream(socket.getOutputStream());
			b.write(new String("HTTP/1.1 "+response.getStatus().value()+" "+response.getStatus().getReasonPhrase()+"\n").getBytes("UTF-8"));
			b.write(new String("Keep-Alive: " + "timeout=5, max=99" + "\n").getBytes("UTF-8"));
			b.write(new String("Server: " + "A good one" + "\n").getBytes("UTF-8"));
			b.write(new String("Connection: " + "Keep-Alive" + "\n").getBytes("UTF-8"));
			b.write(new String("Content-Length: "+writeBody.length()+"\n").getBytes("UTF-8"));
			b.write(new String("Content-Type: "+endpoint.getProduces()+"\n\n").getBytes("UTF-8"));
			b.write(new String(writeBody).getBytes("UTF-8"));
			b.flush();
			b.close();
		}
	}

	public abstract int getPort();
	
	public <T> void addEndpoint(HttpMethod method, String endpoint, MediaType consumes, MediaType produces, T bodyType, EndPoint<T> object) {
		if ( !endpointMap.containsKey(endpoint) )
			endpointMap.put(endpoint, new HashMap<>());
		
		Map<HttpMethod, EndPointWrapper> t = endpointMap.get(endpoint);
		if ( t == null )
			return;
		
		t.put(method, new EndPointWrapper<T>(object, consumes, produces, bodyType));
		System.out.println("Registered endpoint: " + endpoint + " with method: " + method);
	}
	
	public <T> void addEndpoint(HttpMethod method, String endpoint, MediaType produceAndConsume, Class<T> bodyType, EndPoint object) {
		addEndpoint(method, endpoint, produceAndConsume, produceAndConsume, bodyType, object);
	}
	
	public <T> void addEndpoint(HttpMethod method, String endpoint, Class<T> bodyType, EndPoint<T> object) {
		addEndpoint(method, endpoint, MediaType.TEXT_PLAIN, bodyType, object);
	}
	
	public void addEndpoint(HttpMethod method, String endpoint, MediaType consumes, MediaType produces, EndPoint object) {
		addEndpoint(method, endpoint, consumes, produces, Object.class, object);
	}
	
	public void addEndpoint(HttpMethod method, String endpoint, MediaType produceAndConsume, EndPoint object) {
		addEndpoint(method, endpoint, produceAndConsume, produceAndConsume, object);
	}
	
	public void addEndpoint(HttpMethod method, String endpoint, EndPoint object) {
		addEndpoint(method, endpoint, MediaType.TEXT_PLAIN, object);
	}
	
	public void addEndpoint(String endpoint, EndPoint object) {
		addEndpoint(HttpMethod.GET, endpoint, object);
	}
	
    protected <T> HttpRequest<?> parseRequest(String address, int port, InputStream inputStream) throws IOException {
    	
    	// Parse input into strings
		List<String> headerData = readTest(inputStream);
		if ( headerData == null || headerData.size() == 0 )
			return null;
		Object body = headerData.remove(headerData.size()-1);
		
		// Must have 2 strings
		if ( headerData.size() < 2 )
			return null;
		
		// Get some header info
		String[] t1 = headerData.get(0).split(" ");
		HttpMethod method = HttpMethod.valueOf(t1[0]);
		String apiString = t1[1];
		String[] apisplit = apiString.split("\\?", 2);
		String api = apisplit[0];
		Map<String, String> params = new HashMap<>();
		if ( apisplit.length > 1 ) {
			String[] paramsplit = apisplit[1].split("&");
			for (String paramStr: paramsplit) {
				String[] t = paramStr.split("=", 2);
				if ( t.length == 2 ) {
					params.put(t[0], t[1]);
				}
			}
		}
		
		// Create headers
		HttpHeaders headers = new HttpHeaders();
		for (String string : headerData) {
			String[] split = string.split(":", 2);
			if ( split.length != 2 )
				continue;
			String key = split[0].trim();
			String value = split[1].trim();
			
			headers.put(key, value);
		}
		
		// Create request object
		String host = address.replace("0:0:0:0:0:0:0:1", "127.0.0.1");
		URI uri = URI.create("http://" +host + ":" + port + api);
		EndPointWrapper<?> endpoint = getEndPoint(uri.getPath(), method);
		if ( endpoint != null )
			body = getGenericObject(body.toString(), endpoint.getBodyType());
		HttpRequest<?> request = new HttpRequest<>(method, headers, body);
		request.uri = uri;
		request.urlParams = params;
		
		// Return
		return request;
	}
	
    protected static <T> HttpResponse<T> readResponse(HttpURLConnection connection, T type) throws IOException {
    	// Read body
    	byte[] data = readAll(connection.getInputStream());
    	String body = new String(data, Charset.forName("UTF-8"));
    	
    	// Create response headers
    	HttpHeaders headers = new HttpHeaders();
    	Map<String, List<String>> map = connection.getHeaderFields();
    	for (Map.Entry<String, List<String>> entry : map.entrySet()) {
    		headers.put(entry.getKey(), entry.getValue().get(0));
    	}
		
		// Create response object
		T tBody = getGenericObject(body, type);
		HttpResponse<T> request = new HttpResponse<>(HttpStatus.valueOf(connection.getResponseCode()), headers, tBody);
		
		// Return
		return request;
	}

    private static byte[] readAll(InputStream inputStream) throws IOException {
		long TIMEOUT = System.currentTimeMillis()+2000;
		
		// Wait until ready
		BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);
		while(bufferedInput.available() == 0) {
			if ( System.currentTimeMillis() > TIMEOUT ) {
				return null;
			}
		}
		
		// Ready until empty
		byte[] data = new byte[bufferedInput.available()];
		bufferedInput.read(data);
		return data;
    }
    
    private static List<String> readTest(InputStream inputStream) throws IOException {
		long TIMEOUT = System.currentTimeMillis()+1000;
		
		BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);
		while(bufferedInput.available() == 0) {
			if ( System.currentTimeMillis() > TIMEOUT ) {
				return Arrays.asList();
			}
		}
		
    	// Parse input into strings
		List<String> headerData = new ArrayList<String>();
		boolean buildingHeader = true;
		StringBuilder builder = new StringBuilder();
		while(bufferedInput.available() > 0) {
			int c = bufferedInput.read();
			char ch = (char)(c);
			if ( ch == '\r' )
				continue;
			
			if ( ch == '\n' && buildingHeader ) {
				if ( builder.length() == 0 )
					buildingHeader = false;
				
				if ( buildingHeader ) {
					headerData.add(builder.toString().trim());
					builder.setLength(0);
				}
			} else {
				builder.append(ch);
			}
		}
		
		// BODY IS LAST
		headerData.add(builder.toString());
		return headerData;
    }

	private EndPointWrapper getEndPoint(String endpoint, HttpMethod method) {
		Map<HttpMethod, EndPointWrapper> map = endpointMap.get(endpoint);
		if ( map == null )
			return null;
		
		return map.get(method);
	}

	@SuppressWarnings("unchecked")
	protected static <T> T getGenericObject(String body, T type) {
		if ( body == null || body.length() == 0 )
			return (T)null;

		Class<?> c = (Class<?>)type;
		
		// Convert to gson tree
		if ( JsonObject.class.isAssignableFrom(c) ) {
			Type empMapType = new TypeToken<Map<String, Object>>() {}.getType();
			return (T) gson.toJsonTree(gson.fromJson(body, empMapType)).getAsJsonObject();
		}
		
		// json array
		if ( JsonArray.class.isAssignableFrom(c) ) {
			Type empMapType = new TypeToken<List<Object>>() {}.getType();
			Object obj = gson.fromJson(body, empMapType);
			return (T) gson.toJsonTree(obj).getAsJsonArray();
		}
		
		// Convert to map
		if ( Map.class.isAssignableFrom(c) ) {
			Type empMapType = new TypeToken<Map<String, Object>>() {}.getType();
			return gson.fromJson(body, empMapType);
		}
		
		// Convert to list
		if ( List.class.isAssignableFrom(c) ) {
			Type empMapType = new TypeToken<List<Object>>() {}.getType();
			return gson.fromJson(body, empMapType);
		}
		
		// Convert to String
		if ( String.class.isAssignableFrom(c) ) {
			return (T) body.toString();
		}
		
		// Try to parse DTO as fallback
		try {
			return (T) gson.fromJson(body, c);
		} catch(Exception e) {
			return null;
		}
	}
}
