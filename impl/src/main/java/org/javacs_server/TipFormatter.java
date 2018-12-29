package org.javacs_server;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

class TipFormatter {

    private static Document parse(String html) {
        try {
            var xml = "<wrapper>" + html + "</wrapper>";
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            var builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void replaceNodes(Document doc, String tagName, Function<String, String> replace) {
        var nodes = doc.getElementsByTagName(tagName);
        for (var i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            var parent = node.getParentNode();
            var text = replace.apply(node.getTextContent());
            var replacement = doc.createTextNode(text);
            parent.replaceChild(replacement, node);
        }
    }

    private static String print(Document doc) {
        try {
            var tf = TransformerFactory.newInstance();
            var transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            var writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            var wrapped = writer.getBuffer().toString();
            return wrapped.substring("<wrapper>".length(), wrapped.length() - "</wrapper>".length());
        } catch (TransformerException e) {
            throw new RuntimeException(e);
        }
    }

    static String asMarkdown(String html) {
        var doc = parse(html);

        replaceNodes(doc, "i", contents -> String.format("*%s*", contents));
        replaceNodes(doc, "b", contents -> String.format("**%s**", contents));
        replaceNodes(doc, "pre", contents -> String.format("`%s`", contents));
        replaceNodes(doc, "code", contents -> String.format("`%s`", contents));

        return print(doc);
    }
}
