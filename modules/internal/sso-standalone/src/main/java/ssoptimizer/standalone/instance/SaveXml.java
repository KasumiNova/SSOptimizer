package ssoptimizer.standalone.instance;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class SaveXml {
    private static final Set<String> CONTAINERS = Set.of("list", "set", "map", "entry", "e", "l-h-m", "linked-list",
            "linked-hash-set", "linked-hash-map", "tree-set", "tree-map", "vector",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet", "java.util.HashMap");
    private static final Set<String> LAMBDA_FIELDS = Set.of("capturingClass", "functionalInterfaceClass",
            "functionalInterfaceMethodName", "functionalInterfaceMethodSignature", "implClass", "implMethodName",
            "implMethodSignature", "implMethodKind", "instantiatedMethodType");
    private record Frame(String input, String output, boolean container, boolean lambda, String owner, Map<String, String> members) {
        Frame(String input, String output, boolean container, boolean lambda, String owner) {
            this(input, output, container, lambda, owner, owner != null && !container ? new java.util.HashMap<>() : null);
        }
        void note(String raw, String named, String declaring, SaveSymbols symbols) throws IOException {
            if (members == null || !symbols.changedField(named)) return;
            String key = declaring == null ? named : declaring + '/' + named;
            String old = members.putIfAbsent(key, raw);
            if (old != null && !old.equals(raw)) throw new IOException("同一对象的字段映射冲突：" + owner + '/' + old + "、" + raw + " -> " + named);
        }
    }

    private SaveXml() {}

    static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty("jdk.xml.maxElementDepth", 4096);
        factory.setXMLResolver((publicId, systemId, base, ns) -> { throw new XMLStreamException("存档禁止外部引用：" + systemId); });
        return factory;
    }

    static String convert(InputStream in, OutputStream out, SaveSymbols symbols, String file) throws IOException {
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        String root = null;
        XMLStreamReader r = null;
        try {
            r = factory().createXMLStreamReader(in, "UTF-8");
            Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 65536);
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = qualified(r.getPrefix(), r.getLocalName());
                    if (!stack.isEmpty() && stack.peek().lambda) throw new IOException("SerializedLambda 在 capturedArgs 后包含字段：" + tag);
                    boolean plain = r.getNamespaceURI() == null;
                    Frame parent = stack.peek();
                    var layout = symbols.layout();
                    boolean item = parent == null || parent.container;
                    var field = plain && !item ? layout.field(parent.owner, tag, r.getAttributeValue(null, "defined-in"), symbols) : null;
                    String mapped = field != null ? field.xml() : plain ? symbols.element(tag) : tag;
                    if (field == null && !mapped.equals(tag) && !item) throw new IOException("未确认该标签是集合元素类型，需由所属转换器解析：" + tag);
                    if (parent != null) parent.note(tag, mapped, r.getAttributeValue(null, "defined-in"), symbols);
                    if (root == null) root = mapped;
                    String declared = plain ? r.getAttributeValue(null, "cl") : null;
                    String replacement = plain ? r.getAttributeValue(null, "resolves-to") : null;
                    String type = replacement != null ? replacement : declared != null ? declared : mapped;
                    String owner = replacement != null || declared != null ? layout.resolveType(type, symbols)
                            : field != null ? field.type() : plain && item ? layout.resolveType(SaveSymbols.decodeNode(tag), symbols) : null;
                    boolean typed = item || declared != null || replacement != null;
                    boolean lambda = "java/lang/invoke/SerializedLambda".equals(owner) || type.equals("serialized-lambda") || type.equals("java.lang.invoke.SerializedLambda");
                    boolean classValue = "java/lang/Class".equals(owner) || type.equals("java-class") || type.equals("java.lang.Class");
                    if (!typed) {
                        if (field == null && (classValue || lambda)) throw new IOException("无法确认自定义转换器内的类型标签：" + tag);
                        lambda = "java/lang/invoke/SerializedLambda".equals(owner);
                        classValue = "java/lang/Class".equals(owner);
                    }
                    var current = new Frame(tag, mapped, plain && (layout.container(owner) || CONTAINERS.contains(type) || type.endsWith("-array")), plain && lambda, owner);
                    w.write('<'); w.write(mapped);
                    for (int i = 0; i < r.getNamespaceCount(); i++) {
                        String prefix = r.getNamespacePrefix(i);
                        attribute(w, prefix == null ? "xmlns" : "xmlns:" + prefix, r.getNamespaceURI(i));
                    }
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        String key = qualified(r.getAttributePrefix(i), r.getAttributeLocalName(i));
                        String value = r.getAttributeValue(i);
                        if (plain && r.getAttributeNamespace(i) == null && (key.equals("cl") || key.equals("defined-in") || key.equals("resolves-to"))) {
                            value = symbols.type(value);
                        } else if (plain && r.getAttributeNamespace(i) == null && !Set.of("z", "ref", "serialization").contains(key)) {
                            if (key.equals("enum-type") && ("java/util/EnumSet".equals(owner) || "java/util/EnumMap".equals(owner))) value = symbols.type(value);
                            else {
                                var attributeField = layout.field(owner, key, null, symbols);
                                if (attributeField != null) key = attributeField.xml();
                            }
                        }
                        if (plain && r.getAttributeNamespace(i) == null) current.note(r.getAttributeLocalName(i), key, null, symbols);
                        attribute(w, key, value);
                    }
                    w.write('>');
                    if (plain && classValue && r.getAttributeValue(null, "ref") == null) {
                        escape(w, symbols.type(scalar(r)), false);
                        end(w, mapped);
                        continue;
                    }
                    stack.push(current);
                    if (plain && lambda && r.getAttributeValue(null, "ref") == null) {
                        lambdaHeader(r, w, symbols);
                        stack.push(new Frame("capturedArgs", "capturedArgs", true, false, null));
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    end(w, stack.pop().output);
                } else if (r.isCharacters()) {
                    escape(w, r.getText(), false);
                } else if (event == XMLStreamConstants.COMMENT) {
                    w.write("<!--"); w.write(r.getText()); w.write("-->");
                } else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                    w.write("<?"); w.write(r.getPITarget());
                    String data = r.getPIData();
                    if (data != null && !data.isEmpty()) { w.write(' '); w.write(data); }
                    w.write("?>");
                } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new IOException("存档禁止 DTD 和实体声明");
                }
            }
            r.close();
            w.flush();
            return root;
        } catch (XMLStreamException | IOException e) {
            StringBuilder path = new StringBuilder();
            for (var it = stack.descendingIterator(); it.hasNext();) path.append('/').append(it.next().input);
            if (r != null && r.isStartElement()) path.append('/').append(r.getLocalName());
            throw new IOException(file + " " + path + (r == null ? "" : " 行 " + r.getLocation().getLineNumber()
                    + " 列 " + r.getLocation().getColumnNumber()) + "：" + e.getMessage(), e);
        }
    }

    private static void lambdaHeader(XMLStreamReader r, Writer w, SaveSymbols symbols) throws IOException, XMLStreamException {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
        while (r.nextTag() == XMLStreamConstants.START_ELEMENT) {
            String field = r.getLocalName();
            if (r.getNamespaceURI() != null || r.getNamespaceCount() != 0) throw new IOException("不支持带命名空间的 SerializedLambda 字段：" + field);
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < r.getAttributeCount(); i++) {
                String key = r.getAttributeLocalName(i);
                if (r.getAttributeNamespace(i) != null || (!key.equals("z") && !field.equals("capturedArgs"))) {
                    throw new IOException("SerializedLambda 绑定头不支持此属性：" + field + "/@" + key);
                }
                attrs.put(key, r.getAttributeValue(i));
            }
            if (field.equals("capturedArgs")) {
                symbols.lambda(values);
                for (var item : values.entrySet()) {
                    start(w, item.getKey(), attributes.get(item.getKey()));
                    escape(w, item.getValue(), false);
                    end(w, item.getKey());
                }
                start(w, field, attrs);
                return;
            }
            if (!LAMBDA_FIELDS.contains(field) || values.containsKey(field)) throw new IOException("SerializedLambda 绑定头未知或重复：" + field);
            values.put(field, scalar(r));
            attributes.put(field, attrs);
        }
        throw new IOException("SerializedLambda 缺少 capturedArgs");
    }

    private static String scalar(XMLStreamReader r) throws IOException, XMLStreamException {
        StringBuilder text = new StringBuilder();
        while (r.hasNext()) {
            int next = r.next();
            if (next == XMLStreamConstants.END_ELEMENT) return text.toString();
            if (next == XMLStreamConstants.COMMENT) continue;
            if (!r.isCharacters() || r.getTextLength() > 65535 - text.length()) throw new IOException("类型元数据超长或含嵌套元素");
            text.append(r.getText());
        }
        throw new IOException("类型元数据被截断");
    }

    private static String qualified(String prefix, String name) { return prefix == null || prefix.isEmpty() ? name : prefix + ':' + name; }
    private static void start(Writer w, String tag, Map<String, String> attrs) throws IOException {
        w.write('<'); w.write(tag);
        for (var entry : attrs.entrySet()) attribute(w, entry.getKey(), entry.getValue());
        w.write('>');
    }
    private static void end(Writer w, String tag) throws IOException { w.write("</"); w.write(tag); w.write('>'); }
    private static void attribute(Writer w, String name, String value) throws IOException {
        w.write(' '); w.write(name); w.write("=\""); escape(w, value, true); w.write('"');
    }

    private static void escape(Writer w, String text, boolean attr) throws IOException {
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            String entity = switch (text.charAt(i)) {
                case '&' -> "&amp;"; case '<' -> "&lt;"; case '>' -> "&gt;"; case '\r' -> "&#13;";
                case '\n' -> attr ? "&#10;" : null; case '\t' -> attr ? "&#9;" : null; case '"' -> attr ? "&quot;" : null;
                default -> null;
            };
            if (entity != null) { w.write(text, from, i - from); w.write(entity); from = i + 1; }
        }
        w.write(text, from, text.length() - from);
    }
}
