package io.codeatlas.springxml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** web.xml에서 DispatcherServlet의 URL 매핑과 contextConfigLocation을 읽는다. 네임스페이스(javaee, jakartaee, DTD)는 가리지 않는다. */
final class WebXmlReader {

    private WebXmlReader() {
    }

    /** FILE 노드에 붙일 attrs. 남길 것이 없으면 빈 맵. */
    static Map<String, Object> read(XmlElement webApp) {
        List<String> contextConfigLocations = new ArrayList<>();
        Map<String, String> servletClasses = new LinkedHashMap<>();
        Map<String, List<String>> servletConfigLocations = new LinkedHashMap<>();
        Map<String, List<String>> mappings = new LinkedHashMap<>();

        for (XmlElement child : webApp.children) {
            switch (child.localName) {
                case "context-param" -> {
                    if ("contextConfigLocation".equals(childText(child, "param-name"))) {
                        contextConfigLocations.addAll(BeanParser.Names.split(childText(child, "param-value")));
                    }
                }
                case "servlet" -> {
                    String name = childText(child, "servlet-name");
                    if (name == null) {
                        continue;
                    }
                    servletClasses.put(name, childText(child, "servlet-class"));
                    for (XmlElement param : child.children) {
                        if (param.localName.equals("init-param") && "contextConfigLocation".equals(childText(param, "param-name"))) {
                            servletConfigLocations.computeIfAbsent(name, k -> new ArrayList<>())
                                    .addAll(BeanParser.Names.split(childText(param, "param-value")));
                        }
                    }
                }
                case "servlet-mapping" -> {
                    String name = childText(child, "servlet-name");
                    if (name == null) {
                        continue;
                    }
                    for (XmlElement pattern : child.children) {
                        if (pattern.localName.equals("url-pattern")) {
                            String text = pattern.text().strip();
                            if (!text.isEmpty()) {
                                mappings.computeIfAbsent(name, k -> new ArrayList<>()).add(text);
                            }
                        }
                    }
                }
                default -> {
                    // filter, listener 등은 쓰지 않는다
                }
            }
        }

        List<String> dispatchers = new ArrayList<>();
        List<String> servletMappings = new ArrayList<>();
        List<String> dispatcherConfigLocations = new ArrayList<>();
        servletClasses.forEach((name, className) -> {
            if (className != null && BeanRegistry.simpleName(className).equals("DispatcherServlet")) {
                dispatchers.add(name);
                servletMappings.addAll(mappings.getOrDefault(name, List.of()));
                dispatcherConfigLocations.addAll(servletConfigLocations.getOrDefault(name, List.of()));
            }
        });

        Map<String, Object> attrs = new LinkedHashMap<>();
        putList(attrs, "servletMappings", servletMappings);
        putList(attrs, "dispatcherServlets", dispatchers);
        putList(attrs, "servletContextConfigLocations", dispatcherConfigLocations);
        putList(attrs, "contextConfigLocations", contextConfigLocations);
        return attrs;
    }

    private static String childText(XmlElement parent, String localName) {
        for (XmlElement child : parent.children) {
            if (child.localName.equals(localName)) {
                String text = child.text().strip();
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    private static void putList(Map<String, Object> attrs, String key, List<String> values) {
        if (!values.isEmpty()) {
            attrs.put(key, List.copyOf(values));
        }
    }
}
