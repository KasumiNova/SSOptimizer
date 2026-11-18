package ssoptimizer.standalone.instance;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record SaveDescriptor(boolean compressed, Set<String> mods) {
    private enum Kind { ROOT, LIST, MOD, SPEC, VALUE, SKIP }
    private record Node(Kind kind, String tag, String ref, String text, List<Node> children) {
        Node child(String name) throws IOException {
            Node found = null;
            for (Node n : children) if (n.tag.equals(name)) {
                if (found != null) throw new IOException("descriptor.xml 字段重复：" + tag + '/' + name);
                found = n;
            }
            if (found == null) throw new IOException("descriptor.xml 缺少字段：" + tag + '/' + name);
            return found;
        }
    }

    static SaveDescriptor read(InputStream in) throws IOException {
        try {
            var reader = SaveXml.factory().createXMLStreamReader(in, "UTF-8");
            if (reader.nextTag() != XMLStreamConstants.START_ELEMENT || !Set.of("SaveGameData", "com.fs.starfarer.campaign.save.SaveGameData").contains(reader.getLocalName())) {
                throw new IOException("descriptor.xml 根节点必须为 SaveGameData");
            }
            Map<String, Node> ids = new HashMap<>();
            Node root = parse(reader, Kind.ROOT, ids);
            reader.close();
            if (!root.child("gameVersion").text.equals("0.98a-RC8") || !root.child("saveFileVersion").text.equals("0.6")) {
                throw new IOException("descriptor.xml 需要 RC8 的 0.6 存档：" + root.child("gameVersion").text + " / " + root.child("saveFileVersion").text);
            }
            String zip = root.child("compressed").text;
            if (!zip.equals("true") && !zip.equals("false")) throw new IOException("descriptor.xml 的 compressed 无效：" + zip);
            Node list = resolve(root.child("enabledMods"), ids);
            Set<String> mods = new LinkedHashSet<>();
            for (Node row : list.children) {
                Node spec = resolve(resolve(row, ids).child("spec"), ids);
                String id = resolve(spec.child("id"), ids).text;
                if (id.isEmpty() || !mods.add(id)) throw new IOException("descriptor.xml 的 enabledMods 包含空或重复 ID：" + id);
            }
            return new SaveDescriptor(Boolean.parseBoolean(zip), Set.copyOf(mods));
        } catch (XMLStreamException e) {
            throw new IOException("descriptor.xml 格式无效：" + e.getMessage(), e);
        }
    }

    private static Node resolve(Node node, Map<String, Node> ids) throws IOException {
        Set<String> seen = new HashSet<>();
        while (node.ref != null) {
            String reference = node.ref;
            Node target = ids.get(reference);
            if (target == null || !seen.add(reference) || target.kind != node.kind) {
                throw new IOException("descriptor.xml 的 " + node.tag + "/@ref 无法唯一解析：" + reference);
            }
            node = target;
        }
        return node;
    }

    private static Node parse(XMLStreamReader r, Kind kind, Map<String, Node> ids) throws IOException, XMLStreamException {
        String tag = r.getLocalName();
        String z = r.getAttributeValue(null, "z"), ref = r.getAttributeValue(null, "ref");
        StringBuilder text = new StringBuilder();
        var children = new ArrayList<Node>();
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String child = r.getLocalName();
                Kind next = switch (kind) {
                    case ROOT -> switch (child) {
                        case "gameVersion", "saveFileVersion", "compressed" -> Kind.VALUE;
                        case "enabledMods", "allModsEverEnabled" -> Kind.LIST;
                        default -> Kind.SKIP;
                    };
                    case LIST -> Kind.MOD;
                    case MOD -> child.equals("spec") ? Kind.SPEC : Kind.SKIP;
                    case SPEC -> child.equals("id") ? Kind.VALUE : Kind.SKIP;
                    case VALUE, SKIP -> throw new IOException("descriptor.xml 标量包含子元素：" + tag + '/' + child);
                };
                if (next == Kind.SKIP) {
                    int depth = 1;
                    while (depth > 0) {
                        int n = r.next();
                        if (n == XMLStreamConstants.START_ELEMENT) depth++;
                        else if (n == XMLStreamConstants.END_ELEMENT) depth--;
                    }
                } else {
                    if (children.size() >= 65536) throw new IOException("descriptor.xml 模组条目过多：" + tag);
                    children.add(parse(r, next, ids));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                Node result = new Node(kind, tag, ref, text.toString(), children);
                if (z != null && ids.putIfAbsent(z, result) != null) throw new IOException("descriptor.xml ID 重复：" + z);
                if (ref != null && (!children.isEmpty() || !text.isEmpty())) throw new IOException("descriptor.xml 的引用同时含有值：" + tag);
                return result;
            } else if (r.isCharacters() && kind == Kind.VALUE) {
                if (text.length() + r.getTextLength() > 65535) throw new IOException("descriptor.xml 标量过长：" + tag);
                text.append(r.getText());
            } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                throw new IOException("descriptor.xml 禁止 DTD 和实体声明");
            }
        }
        throw new IOException("descriptor.xml 被截断：" + tag);
    }
}
