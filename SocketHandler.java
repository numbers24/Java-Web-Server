/**
 * @author Christopher DeAngelis
 * @author Steven Rodriguez
 */



import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.net.*;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;

public class SocketHandler implements Runnable {

    private Socket s; // Socket passed to the current thread
    private BufferedReader req; // Used to read incoming HTTP request
    private BufferedOutputStream resp; // Output stream for response
    private String ifModified;
    private ArrayList<String> request;

    public SocketHandler(Socket s) {
        this.s = s;
    }

    public void run() {
        try {
            s.setSoTimeout(5000); //Set timeout to 5 seconds
            req = new BufferedReader(new InputStreamReader(s.getInputStream())); // Setup up reader to read in request
            resp = new BufferedOutputStream(s.getOutputStream()); // Setup output stream of socket for responses

            String first = req.readLine();
            ifModified = req.readLine();
            request = new ArrayList<String>();
            request.add(first);
            request.add(ifModified);
            while(req.ready()){request.add(req.readLine());}

            parseRequest(first);

        } catch (SocketTimeoutException e) {
            write(Response.getErrorMessage(408)); //Catch timeout exception after 5 seconds and send 408 timeout error response
        } catch (IOException e) {
            closeStreams();
        }
    }

    /**
     * This method parses incoming request and responds with the appropriate error message. If passed without errors gets handed to the appropriate command method
     * @param request The request string
     */
    public void parseRequest(String request) {

        //If request is just empty lines send bad request
        if (request == null || request.trim().isEmpty()) {
            write(Response.getErrorMessage(400));
            closeStreams();
            return;
        }
        String[] firstLine = request.split(" ");

        if (firstLine.length == 0) {
            write(Response.getErrorMessage(400)); // Bad request NULL request
            return;
        }

        String command = firstLine[0];
        if (command.equals("GET") || command.equals("POST") || command.equals("HEAD")) {
            // Do nothing and continue
        } else if (command.equals("DELETE") || command.equals("PUT") || command.equals("LINK") || command.equals("UNLINK")) {
            write(Response.getErrorMessage(501)); // Return 501 not implemented
            return;
        } else {
            write(Response.getErrorMessage(400)); // Command doesn't exist return bad request
            return;
        }

        String source = firstLine[1];
        if (source.charAt(0) == '/') {
            source = "." + source;
        }

        // Check whether source path is valid
        File sourceFile = new File(source);
        try {
            sourceFile.getCanonicalPath();
        } catch (Exception e) {
            write(Response.getErrorMessage(400)); //Send 400 Bad request when not valid
            return;
        }

        if (firstLine.length < 3) {
            write(Response.getErrorMessage(400)); // HTTP version number missing 400 Bad request
            return;
        }

        String version = firstLine[2];
        if (version.matches("HTTP/0.\\d") || version.matches("HTTP/1.0")) {
            // Do nothing version is good
        } else if (version.matches("HTTP/\\d.\\d")) {
            write(Response.getErrorMessage(505)); // Send 505 HTTP Version Not Supported
            return;
        } else {
            write(Response.getErrorMessage(400)); // Invalid HTTP Version 400 Bad Request
            return;
        }

        // Hand off the file to the appropriate method
        switch (command) {
            case "HEAD":
                head(sourceFile);
                break;
            case "GET":
                get(sourceFile);
                break;
            case "POST":
                post(sourceFile);
                break;
        }

        return;
    }

    /**
     * This method parses incoming request and responds with the appropriate error message. If passed without errors gets handed to the appropriate command method
     * @param request The request string
     */
    public void get(File sourceFile) {
        if (!sourceFile.exists()) {
            write(Response.getErrorMessage(404)); //If file does not exist send 404 Not found
            return;
        } else if (!sourceFile.canRead()) {
            write(Response.getErrorMessage(403)); //If file unreadable 403 forbidden
            return;
        }

        //Checks if if-modified header exists, if it does it checks if the file has been modified since the given time. If it hasn't been it sends a 304 Not Modified
        if (ifModified != null) {
            if(!ifModified.trim().isEmpty()) {
                String date = ifModified.substring(ifModified.indexOf(" ") + 1);
                long modifiedSince = Response.convertToLong(date);
                if (modifiedSince != 0) {
                    if (sourceFile.lastModified() <= modifiedSince) {
                        write(Response.getErrorMessage(304));
                        return;
                    }
                }
            }
        }

        Response r = new Response(sourceFile); //Create new response object with source file
        String lasttime = "";

        // handles the date format included the variable for both the encoded and decoded date
        String date;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String newDate = sdf.format(new Date());
        String encodedDate = "";
        String decodedDate = "";
        int i = 0;

        // checks if this is the first time the client has accessed the server, reading the cookie if it's there
        boolean hasVisited = false;
        for (String line : request) {
            String tokens[] = line.split(" ");
            switch (tokens[0]) {
                case "Cookie:":
                    try {
                        lasttime = tokens[1];
                        hasVisited = true;
                        i = request.indexOf(line);
                    } catch (ArrayIndexOutOfBoundsException e){
                        i = request.indexOf(line);
                    }
                    break;
            }
        }

        try {
            encodedDate = URLEncoder.encode(newDate, "UTF-8"); // encodes the date, if it can't throws an AssertionError
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 is unknown");
        }

        if(hasVisited){
            date = lasttime.split("=")[1];
            try {
                decodedDate = URLDecoder.decode(date, "UTF-8"); // decodes the date, if it can't throws an AssertionError
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError("UTF-8 is unknown");
            }


            // Handles for the html file to send to a return client, including the date from the old cookie
            request.set(i,"Set-Cookie: lasttime="+encodedDate+"\n");
            File index_seen = new File("./index_seen.html");
            String htmlmsg = "<html>\n<body>\n<h1>CS 352 Welcome Page</h1>\n<p>\nWelcome back! Your last visit was at: " + decodedDate + "\n<p>\n</body>\n</html>";
            if (decodedDate.compareTo(newDate) > 0){
                //treat like a new user
                htmlmsg = "<html>\n<body>\n<h1>CS 352 Welcome Page</h1>\n<p>\nWelcome! We have not seen you before.\n<p>\n</body>\n</html>";
            }
            try {
                FileWriter writer = new FileWriter(index_seen);
                writer.write(htmlmsg);
                writer.close();
                InputStream in = this.getClass().getClassLoader()
                        .getResourceAsStream("./index_seen.html");
                String link = new BufferedReader(new InputStreamReader(in))
                        .lines().collect(Collectors.joining("\n"));
                request.add(link);
            } catch (Exception e) {
            }
        }
        // Handles for the html file to send to a new client, then sets the date for the cookie
        else{
            File index = new File("./index.html");
            System.out.println(index.getAbsolutePath());
            String htmlmsg = "<html>\n<body>\n<h1>CS 352 Welcome Page</h1>\n<p>\nWelcome! We have not seen you before.\n<p>\n</body>\n</html>";
            try {
                FileWriter writer = new FileWriter(index);
                writer.write(htmlmsg);
                writer.close();
                InputStream in = this.getClass().getClassLoader()
                        .getResourceAsStream("./index.html");
                String link = new BufferedReader(new InputStreamReader(in))
                        .lines().collect(Collectors.joining("\n"));
                request.add(link);
                if(i==0){
                    request.set(4,"Set-Cookie: lasttime="+encodedDate+"\n");
                }
                else{
                    request.set(i,"Set-Cookie: lasttime="+encodedDate+"\n");
                }
            } catch (Exception e2) {}
        }

        StringBuilder sb = new StringBuilder();
        for(String line : request){
            if(line.equals("")) continue;
            sb.append(line+"\r\n");
            // standard get method output for the headers
        }
        System.out.println(sb.toString());
        String headers;
        byte[] fileBytes;
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
            headers = sb.toString() + "\r\n"; //Creates headers
            bw.write(headers);
            bw.flush();

            Thread.sleep(250);
            closeStreams();
        } catch (IOException e) {
            closeStreams();
        } catch (InterruptedException e) {
            closeStreams();
        }

        return;

    }

    /**
     * POST method verifies certain headers then creates url from file path and parameters before the cgi file is run
     * @param request The request string
     */
    public void post(File sourceFile) {
        if (!sourceFile.exists()) {
            write(Response.getErrorMessage(404)); //If file does not exist send 404 Not found
            return;
        } else if (!sourceFile.canRead()) {
            write(Response.getErrorMessage(403)); //If file unreadable 403 forbidden
            return;
        }

        //reset req and parse the entire request
        try{
            req = new BufferedReader(new InputStreamReader(s.getInputStream()));

            String[] data = new String[5]; //holds the request data
            boolean type = false; //content type
            for(String line: request){
                String tokens[] = line.split(" ");
                switch (tokens[0]){
                    case "POST" : data[0]=tokens[1];
                    case "From:": data[1] = tokens[1]; break;
                    case "User-Agent:": data[2] = tokens[1]; break;
                    case "Content-Type:" : type = true; break;
                    case "Content-Length:": data[3] = tokens[1]; break;
                    default:
                        if(line.indexOf(":")<0){
                            if(line.equals("")) continue;
                            else data[4]=line;
                        }
                        else{
                            write(Response.getErrorMessage(55));
                        } break; //cannot parse and respond
                }
            }

            if(!type) write(Response.getErrorMessage(500));  // sends error if there is no Content-Type
            if(!data[0].endsWith(".cgi")) write(Response.getErrorMessage(405)); // sends error if there is no cgi given in path

            //decode params
            String decoded =data[4];
            if(decoded.contains("!!")){
                decoded = decoded.replaceAll("!!", "!");
            }
            if(decoded.contains("!@")){
                decoded = decoded.replaceAll("!@", "@");
            }
            if(decoded.contains("!")){
                decoded= decoded.replaceAll("!", "*");
            }

            //create the command
            List<String> command= new ArrayList<String>();
            command.add(data[0]);
            if(decoded!=null)command.add(decoded);
            //create the process
            ProcessBuilder pb = new ProcessBuilder(command);
            //build key map
            Map<String,String> map = pb.environment();
            map.put("SCRIPT_NAME",data[0]);
            map.put("SERVER_NAME","me@mycomputer");
            map.put("SERVER_PORT", s.getPort()+"");
            map.put("HTTP_FROM",data[1]);
            map.put("HTTP_USER_AGENT",data[2]);
            map.put("CONTENT_LENGTH",data[3]);
            //start process
            Process process = pb.start();
            //print results of process
            BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while(output.ready()){
                System.out.println(output.readLine());
            }
            //get the payload
            //i give up idk what i'm doing and i can't find any resources that help me figure it out



        } catch (Exception e){}
        return;

    }



    /**
     * HEAD method returns just the headers of the file without the file contents
     * @param request The request string
     */
    public void head(File sourceFile) {

        if (!sourceFile.exists()) {
            write(Response.getErrorMessage(404)); //If file not found send 404 not found
            return;
        } else if (!sourceFile.canRead()) {
            write(Response.getErrorMessage(403)); //If file unreadable found send 403 Forbidden
            return;
        }


        //Gets response headers and writes them to output stream
        Response r = new Response(sourceFile);

        String headers;
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(sourceFile.toPath());
            headers = r.getResponseHeaders(fileBytes.length);
            write(headers);
        } catch (IOException e) {
            write(Response.getErrorMessage(500));
        }

        return;

    }


    /**
     * Takes in a response string and writes it out to the buffer and closes the streams/socket
     */
    public void write(String response) {
        try {
            resp.write(response.getBytes());
            resp.flush();
            Thread.sleep(250);
            closeStreams();
        } catch (IOException e) {
            closeStreams();
        } catch (InterruptedException e) {
            closeStreams();
        }
    }

    /**
     * Closes all streams
     */
    public void closeStreams() {
        try {
            resp.close(); //Close output stream
            req.close(); //Close input stream
            s.close(); //Close socket
        } catch (IOException e) {

        }

        return;
    }
}


/**
 * Response class to properly structure HTTP response messages
 */
class Response {

    File source;

    public Response(File source) {
        this.source = source;
    }

    /**
     * Gets response headers bases on file info
     * @param length
     * @return response headers in string format
     */
    public String getResponseHeaders(int length) {
        StringBuilder s = new StringBuilder();
        s.append("HTTP/1.0 200 OK\r\n");
        s.append("Content-Type: " + getMimeType(source.getAbsolutePath()) + "\r\n");
        s.append("Content-Length: " + length + "\r\n");
        s.append("Last-Modified: " + convertDateFormat(source.lastModified()) + "\r\n");
        s.append("Content-Encoding: " + "identity\r\n");
        s.append("Allow: GET, POST, HEAD\r\n");
        s.append("Expires: " + convertDateFormat(System.currentTimeMillis() + 10000000) + "\r\n");

        return s.toString();
    }

    /**
     * Finds file extension from source and returns with corresponding mime type
     * @param source Source file path
     * @return the mime type
     */
    public static String getMimeType(String source) {
        String extension = source.substring(source.lastIndexOf(".") + 1);

        String mime = "";
        switch (extension) {
            case "txt":
                mime = "text/plain";
                break;
            case "html":
                mime = "text/html";
                break;
            case "gif":
                mime = "image/gif";
                break;
            case "jpeg":
                mime = "image/jpeg";
                break;
            case "png":
                mime = "image/png";
                break;
            case "pdf":
                mime = "application/pdf";
                break;
            case "x-gzip":
                mime = "application/x-gzip";
                break;
            case "zip":
                mime = "application/zip";
                break;
            default:
                mime = "application/octet-stream";
        }

        return mime;
    }

    /**
     * Takes error code as input and returns correspoding error message
     * @param statusCode http error code
     * @return error message string
     */
    public static String getErrorMessage(int statusCode) {
        String output = "";
        switch (statusCode) {
            case 304:
                output = "HTTP/1.0 " + statusCode + " Not Modified" + "\r\n" + "Expires: " + convertDateFormat(System.currentTimeMillis() + 10000000);
                break;
            case 400:
                output = "HTTP/1.0 " + statusCode + " Bad Request";
                break;
            case 403:
                output = "HTTP/1.0 " + statusCode + " Forbidden";
                break;
            case 404:
                output = "HTTP/1.0 " + statusCode + " Not Found";
                break;
            case 405:
                output = "HTTP/1.0 " + statusCode + " Method Not Allowed";
            case 408:
                output = "HTTP/1.0 " + statusCode + " Request Timeout";
                break;
            case 411:
                output = "HTTP/1.0 " + statusCode + " 411 Length Required";
            case 500:
                output = "HTTP/1.0 " + statusCode + " Internal Service Error";
                break;
            case 501:
                output = "HTTP/1.0 " + statusCode + " Not Implemented";
                break;
            case 503:
                output = "HTTP/1.0 " + statusCode + " Service Unavailable";
                break;
            case 505:
                output = "HTTP/1.0 " + statusCode + " HTTP Version Not Supported";
                break;
        }
        return output;
    }

    /**
     * Takes time in long and converts it to http date format
     * @param time date in long format
     * @return date in HTTP date format
     */
    public static String convertDateFormat(long time) {
        Date date = new Date(time);
        SimpleDateFormat httpFormatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        httpFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        String httpDate = httpFormatter.format(date);

        return httpDate;
    }

    /**
     * Converts dateFormat back to long
     * @param time date in long format
     * @return date in long format or 0 if cannot be parsed
     */
    public static long convertToLong(String date) {
        SimpleDateFormat httpFormatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        httpFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));

        Date dateObj;
        long inLong;
        try {
            dateObj = httpFormatter.parse(date);
            inLong = dateObj.getTime();
        } catch (ParseException e) {
            inLong = 0;
        }

        return inLong;
    }

}}