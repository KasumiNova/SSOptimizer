package ssoptimizer.standalone.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalFormatTest {
    @TempDir Path temp;
    private static final String TINY = "tiny\t2\t0\tobf\tnamed\n\tescaped-names\n"
            + "c\tx/a$b_c\tsim/Outer$Inner_x\n\tm\t([Lx/a$b_c;)V\tif.new\tconsume\n";

    private SaveSymbols symbols(String tiny) throws IOException {
        Path file = temp.resolve("mapping.tiny.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) { out.write(bytes(tiny)); }
        return SaveSymbols.read(file);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static ByteArrayInputStream input(String s) { return new ByteArrayInputStream(bytes(s)); }
    private static Document transform(String xml, SaveSymbols symbols) throws Exception {
        var out = new ByteArrayOutputStream();
        SaveXml.convert(input(xml), out, symbols, "fixture.xml");
        return DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(new ByteArrayInputStream(out.toByteArray()));
    }
    private static Element item(Document document, String name) { return (Element) document.getElementsByTagName(name).item(0); }

    @Test void xmlNamesDescriptorsAndAttributesPreserveAliasesReferencesAndPlayerText() throws Exception {
        String old = "x.a$b_c";
        String xml = """
                <CampaignEngine z="a"><list><x.a_-b__c-array z="b"/><java-class>[[Lx.a$b_c;</java-class></list>
                <object cl="x.a$b_c[][]" defined-in="x.a$b_c" ref="a"/><other cl="Flt"/>
                <player alias="x.a$b_c" class="x.a$b_c" label="a&#9;b&#10;c&#13;d">x.a$b_c</player>
                <implClass>x/a$b_c</implClass><capturingClass>x.a$b_c</capturingClass></CampaignEngine>""";
        Document doc = transform(xml, symbols(TINY));
        assertEquals("b", item(doc, "sim.Outer_-Inner__x-array").getAttribute("z"));
        assertEquals("[[Lsim.Outer$Inner_x;", item(doc, "java-class").getTextContent());
        assertEquals("sim.Outer$Inner_x[][]", item(doc, "object").getAttribute("cl"));
        assertEquals("sim.Outer$Inner_x", item(doc, "object").getAttribute("defined-in"));
        assertEquals("a", item(doc, "object").getAttribute("ref"));
        assertEquals("Flt", item(doc, "other").getAttribute("cl"));
        var player = item(doc, "player");
        assertEquals(old, player.getTextContent()); assertEquals(old, player.getAttribute("class"));
        assertEquals(old, player.getAttribute("alias")); assertEquals("a\tb\nc\rd", player.getAttribute("label"));
        assertEquals("x/a$b_c", item(doc, "implClass").getTextContent());
        assertEquals(old, item(doc, "capturingClass").getTextContent());
    }

    @Test void lambdaBindingUsesExactOwnerAndDescriptorWithoutRewritingCapturedStrings() throws Exception {
        String xml = """
                <callback resolves-to="serialized-lambda"><capturingClass>historical-alias</capturingClass>
                <functionalInterfaceClass>x/a$b_c</functionalInterfaceClass><functionalInterfaceMethodName>if.new</functionalInterfaceMethodName>
                <functionalInterfaceMethodSignature>(I)V</functionalInterfaceMethodSignature><implClass>x/a$b_c</implClass>
                <implMethodName>if.new</implMethodName><implMethodSignature>([Lx/a$b_c;)V</implMethodSignature><implMethodKind>6</implMethodKind>
                <instantiatedMethodType>(I)Lx/a$b_c;</instantiatedMethodType><capturedArgs><st>x.a$b_c</st>
                <x.a_-b__c z="7"/></capturedArgs></callback>""";
        Document doc = transform(xml, symbols(TINY));
        assertEquals("historical-alias", item(doc, "capturingClass").getTextContent());
        assertEquals("sim/Outer$Inner_x", item(doc, "functionalInterfaceClass").getTextContent());
        assertEquals("if.new", item(doc, "functionalInterfaceMethodName").getTextContent());
        assertEquals("consume", item(doc, "implMethodName").getTextContent());
        assertEquals("([Lsim/Outer$Inner_x;)V", item(doc, "implMethodSignature").getTextContent());
        assertEquals("(I)Lsim/Outer$Inner_x;", item(doc, "instantiatedMethodType").getTextContent());
        assertEquals("x.a$b_c", item(doc, "st").getTextContent());
        assertEquals("7", item(doc, "sim.Outer_-Inner__x").getAttribute("z"));
    }

    @Test void unknownConverterAndConflictingNamespacesDoNotGuess() throws Exception {
        var syms = symbols(TINY);
        assertThrows(IOException.class, () -> transform("<plugin><x.a_-b__c/></plugin>", syms));
        assertThrows(IOException.class, () -> symbols(TINY + "c\tx/other\tsim/Outer$Inner_x\n"));
        assertThrows(IOException.class, () -> symbols(TINY + "c\tx/a$b_c\tx/other\n"));
        SaveSymbols ambiguous = symbols(TINY + "c\tx/z\tx/a$b_c\n");
        assertThrows(IOException.class, () -> ambiguous.type("x.a$b_c"));
    }

    @Test void metadataMustBeAScalarAndDescriptorsMustBeComplete() throws Exception {
        var s = symbols(TINY);
        for (String desc : new String[]{"(V)V", "([V)I", "(Lx/a$b_c)V", "()II", "([)"}) {
            assertThrows(IOException.class, () -> s.methodDescriptor(desc));
        }
        assertThrows(IOException.class, () -> transform("<cb resolves-to='serialized-lambda'><capturedArgs/></cb>", s));
        assertThrows(IOException.class, () -> transform("<java-class><st>x.a$b_c</st></java-class>", s));
    }

    @Test void trustedClassDeclarationsDistinguishInheritedFieldsAliasesAndUnrelatedNames() throws Exception {
        SaveSymbols mapping = symbols("tiny\t2\t0\tobf\tnamed\nc\tcom/fs/sample/Base\tcom/fs/sample/Base\n"
                + "\tf\tLjava/lang/String;\tj\tappearance\n");
        Path src = Files.createDirectories(temp.resolve("src"));
        Path classes = Files.createDirectory(temp.resolve("classes"));
        Path factory = src.resolve("CampaignGameManager.java");
        Path dto = src.resolve("Sample.java");
        Path stream = src.resolve("XStream.java");
        Files.writeString(stream, "package com.thoughtworks.xstream; public class XStream {public void alias(String s, Class<?> c) {} public void aliasAttribute(Class<?> c, String f, String a) {}}");
        Files.writeString(dto, "package com.fs.sample; class Base { String j; } public class Sample extends Base { public Child child; public static class Child { public String j; } }");
        Files.writeString(factory, """
                package com.fs.starfarer.campaign.save;
                import com.fs.sample.Sample; import com.thoughtworks.xstream.XStream;
                class CampaignGameManager { XStream getXStream(String version) { XStream x = new XStream();
                x.alias("CampaignEngine", Sample.class); x.aliasAttribute(Sample.class, "child", "c"); return x; } }
                """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                factory.toString(), dto.toString(), stream.toString()));
        Path jar = temp.resolve("game.jar");
        try (var paths = Files.walk(classes); var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace(java.io.File.separatorChar, '/')));
                Files.copy(file, out); out.closeEntry();
            }
        }
        mapping.bindGame(java.util.List.of(jar));
        Document doc = transform("<CampaignEngine><j>j</j><c><j>j</j></c></CampaignEngine>", mapping);
        assertEquals("j", item(doc, "appearance").getTextContent());
        assertEquals("j", item(doc, "c").getFirstChild().getNodeName());
        for (String collision : new String[]{"<j>x</j><appearance>y</appearance>", "<appearance>y</appearance><j>x</j>"}) {
            assertThrows(IOException.class, () -> transform("<CampaignEngine>" + collision + "</CampaignEngine>", mapping));
        }
    }

    @Test void dtdAndExternalEntitiesAreRejected() throws Exception {
        var symbols = symbols(TINY);
        for (String dtd : new String[]{"<!DOCTYPE list>", "<!DOCTYPE list [<!ENTITY x SYSTEM 'file:/never/open'>]>"}) {
            assertThrows(IOException.class, () -> transform(dtd + "<list/>", symbols));
        }
    }

    private static final String DESCRIPTOR = """
            <SaveGameData z="a"><gameVersion>0.98a-RC8</gameVersion><saveFileVersion>0.6</saveFileVersion><compressed>true</compressed>
            <allModsEverEnabled z="b"><EnabledModData z="1"><spec z="2"><id> old </id></spec></EnabledModData>
            <EnabledModData z="3"><spec z="4"><id>active mod </id></spec></EnabledModData></allModsEverEnabled>
            <enabledMods><EnabledModData><spec ref="4"/></EnabledModData></enabledMods></SaveGameData>""";

    @Test void descriptorResolvesModReferencesAndDistinguishesCurrentFromHistoricalMods() throws Exception {
        SaveDescriptor d = SaveDescriptor.read(input(DESCRIPTOR));
        assertTrue(d.compressed()); assertEquals(Set.of("active mod "), d.mods());
        String byList = DESCRIPTOR.replace("<enabledMods><EnabledModData><spec ref=\"4\"/></EnabledModData></enabledMods>", "<enabledMods ref='b'/>");
        assertEquals(Set.of(" old ", "active mod "), SaveDescriptor.read(input(byList)).mods());
        for (String ref : new String[]{"1", "missing"}) assertThrows(IOException.class,
                () -> SaveDescriptor.read(input(DESCRIPTOR.replace("ref=\"4\"", "ref='" + ref + "'"))));
        assertThrows(IOException.class, () -> SaveDescriptor.read(input(DESCRIPTOR.replace("z=\"3\"", "z='1'"))));
    }

    @Test void zipAcceptsOnlyOneCampaignAndPreservesCompressedStructure() throws Exception {
        var symbols = symbols(TINY);
        for (var names : new String[][]{{"campaign.xml"}, {"wrong.xml"}, {"campaign.xml", "second.xml"}, {"../campaign.xml"}}) {
            Path input = Files.createTempFile(temp, "old-", ".zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
                for (String name : names) {
                    zip.putNextEntry(new ZipEntry(name)); zip.write(bytes("<CampaignEngine><obj cl='x.a$b_c'/></CampaignEngine>")); zip.closeEntry();
                }
            }
            Path output = temp.resolve(input.getFileName() + ".named");
            if (names.length == 1 && names[0].equals("campaign.xml")) {
                HistoricalSave.convertZip(input, output, symbols);
                try (var zip = new ZipFile(output.toFile()); var in = zip.getInputStream(zip.getEntry("campaign.xml"))) {
                    assertEquals("sim.Outer$Inner_x", item(DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(in), "obj").getAttribute("cl"));
                }
            } else assertThrows(IOException.class, () -> HistoricalSave.convertZip(input, output, symbols));
        }
    }
}
