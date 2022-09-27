package com.github.egwepas.pop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {
    private static Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            String configurationPath = System.getProperty("user.home") + File.separator + ".pop.yaml";

            ObjectMapper objectMapper = new ObjectMapper(YAMLFactory.builder().build());
            File configurationFile = new File(configurationPath);

            if (!configurationFile.exists()) {
                Files.copy(ClassLoader.getSystemResourceAsStream(".pop.yaml"), Paths.get(configurationPath), StandardCopyOption.REPLACE_EXISTING);
            }

            Configuration configuration = objectMapper.readValue(configurationFile, Configuration.class);

            AtomicReference<Invocable> pac = new AtomicReference<>();
            if (null != configuration.pac) {
                OkHttpClient client = new OkHttpClient();

                Request request = new Request.Builder()
                        .url(configuration.pac)
                        .build();

                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try (Response response = client.newCall(request).execute()) {
                            PACSupportMethods pacSupportMethods = new PACSupportMethods();
                            ScriptEngineManager manager = new ScriptEngineManager();
                            ScriptEngine engine = manager.getEngineByName("JavaScript");
                            Function<String, Boolean> isPlainHostName = pacSupportMethods::isPlainHostName;

                            engine.put("isPlainHostName", isPlainHostName);
                            BiFunction<String, String, Boolean> shExpMatch = pacSupportMethods::shExpMatch;
                            engine.put("shExpMatch", shExpMatch);
                            engine.eval(new StringReader(response.body().string()));
                            pac.set((Invocable) engine);
                            timer.cancel();
                            timer.purge();
                        } catch (Exception e){
                            log.warn("Failed to fetch PAC, will retry", e);
                        }
                    }
                } ,0, 30_000);
            }

            ChainedProxyManager chainedProxyManager = (httpRequest, chainedProxies) -> {
                try {
                    String uri = httpRequest.getUri();
                    String host = uriToHost(uri);

                    // try to resolve agains patterns
                    for (Map.Entry<String, List<String>> suffixesByProxy : configuration.proxies.entrySet()) {
                        String proxy = suffixesByProxy.getKey();
                        for (String suffix : suffixesByProxy.getValue()) {
                            if (host.endsWith(suffix)) {
                                chainedProxies.add(uriToAdapter(proxy));
                                return;
                            }
                        }
                    }

                    // try to resolve against PAC if any
                    Invocable pacInvocable = pac.get();
                    if (null != pacInvocable) {
                        try {
                            String result = pacInvocable.invokeFunction("FindProxyForURL", uri, host).toString();
                            Arrays.stream(result.split(";")).map(String::trim).filter(str -> !str.isEmpty()).forEach(str -> {
                                if (str.equals("DIRECT")) {
                                    chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
                                } else {
                                    chainedProxies.add(uriToAdapter(str.substring(str.indexOf(' ') + 1).trim()));
                                }
                            });

                        } catch (Exception e) {
                            log.warn("Failed to invoke function on pac", e);
                        }
                    } else {
                        // fallback to DIRECT
                        chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
                    }
                } finally {
                    //log.debug("for URI : {}, returning proxies {}", httpRequest.getUri(), chainedProxies.stream().map(p -> p.getChainedProxyAddress()).collect(Collectors.toList()));
                }
            };

            HttpProxyServer server =
                    DefaultHttpProxyServer.bootstrap()
                            .withPort(8080)
                            .withChainProxyManager(chainedProxyManager)
                            .start();
        } catch (Exception e) {
            log.error("Exception occured", e);
        }
    }

    private static String uriToHost(String uri) {
        int offset = uri.indexOf("://");
        if (-1 != offset) {
            uri = uri.substring(offset + 3);
        }

        offset = uri.indexOf("/");
        if (-1 != offset) {
            uri = uri.substring(0, offset);
        }

        offset = uri.indexOf(":");
        if (-1 != offset) {
            uri = uri.substring(0, offset);
        }

        return uri.trim().toLowerCase();
    }

    private static ChainedProxyAdapter uriToAdapter(String uri) {
        int offset = uri.indexOf(':');
        String uriHost;
        int uriPort;
        if (offset == -1) {
            uriHost = uri;
            uriPort = 80;
        } else {
            uriHost = uri.substring(0, offset);
            uriPort = Integer.parseInt(uri.substring(offset + 1));
        }

        return new ChainedProxyAdapter() {
            @Override
            public InetSocketAddress getChainedProxyAddress() {
                return new InetSocketAddress(uriHost, uriPort);
            }
        };
    }
}