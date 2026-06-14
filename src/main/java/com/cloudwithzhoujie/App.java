package com.cloudwithzhoujie;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.util.Scanner;

public class App {


// Security Hotspot Demo
private static final String API_KEY = "ZHOUJIE_DEMO_SECRET";

public static void main(String[] args) throws Exception {

    System.out.println("Starting ZhouJie DevSecOps Demo...");

    String username = "guest";

    try {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter username: ");
        if (sc.hasNextLine()) {
            username = sc.nextLine();
        }
    } catch (Exception e) {
        // ignore for non-interactive mode
    }

    if ("admin".equals(username)) {
        System.out.println("Welcome, admin!");
    } else {
        System.out.println("Hello, " + username);
    }

    int port = 8080;

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

    server.createContext("/", exchange -> {
        String body = brandHtml();

        byte[] out = body.getBytes();

        exchange.getResponseHeaders().add(
            "Content-Type",
            "text/html; charset=UTF-8"
        );

        exchange.sendResponseHeaders(200, out.length);

        OutputStream os = exchange.getResponseBody();
        os.write(out);
        os.close();
    });

    System.out.println("ZhouJie DevSecOps Demo running on port " + port);

    server.start();
}

public static String brandHtml() {

    return """
    <html>
    <head>
        <title>ZhouJie DevSecOps Platform</title>
    </head>
    <body>
        <h1>ZhouJie DevSecOps Platform</h1>
        <p>Jenkins + SonarQube + Trivy + Docker + Kubernetes</p>
    </body>
    </html>
    """;
}


}

