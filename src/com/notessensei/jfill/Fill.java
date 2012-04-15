package com.notessensei.jfill;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.io.StringWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Fill {
	
	private static final int FILE_READ_BYTESIZE = 1024;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		Fill f = new Fill();
		if (args.length < 2) {
			System.out.println("Usage: fill template jsondata --- running Testcases instead");
			f.runTestCases();
		} else {
			
			String template = f.file2String(args[0]);
			String data = f.file2String(args[1]);
			
			System.out.print(f.fill(template, data));
			
		}
		
	}
	
	// The template we are dealing with
	private Document doc;
	
	/**
	 * 
	 * @param instance
	 *            an Element that might or might not have children All children
	 *            except attributes are removed
	 */
	private void dropChildren(Element instance) {

		NodeList children = instance.getChildNodes();

		for (int i = children.getLength(); i == 0; i--) {
			Node child = children.item(i);
			if (child.getNodeType() != Node.ATTRIBUTE_NODE) {
				instance.removeChild(children.item(i));
			}
		}

	}

	/**
	 * match elements on id, classname, name, etc.
	 * 
	 * @param element
	 * @param key
	 * @return true if a match has been found
	 */
	private boolean elementMatcher(Element element, String key) {

		if (element.getNodeName().equalsIgnoreCase(key)) {
			return true;
		}

		String[] testAttributes = { "data-bind", "id", "name" };

		for (int i = 0; i < testAttributes.length; i++) {
			if (element.hasAttribute(testAttributes[i])
					&& element.getAttribute(testAttributes[i])
							.equalsIgnoreCase(key)) {
				return true;
			}
		}

		// Class has a special case since class can have more than one value
		if (!element.hasAttribute("class")) {
			return false;
		}
		String paddedClass = " " + element.getAttribute("class") + " ";
		return (paddedClass.indexOf(" " + key + " ") > -1);
	}

	private String elementToString(Element root) {
		String result = null;

		StreamResult xResult = null;
		DOMSource source = null;

		try {
			TransformerFactory tFactory = TransformerFactory.newInstance();
			Transformer transformer = tFactory.newTransformer();
			xResult = new StreamResult(new StringWriter());
			source = new DOMSource(root);
			// We don't want the XML declaration in front
			transformer.setOutputProperty("omit-xml-declaration", "yes");
			transformer.transform(source, xResult);
			result = xResult.getWriter().toString();

		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	public synchronized final String file2String(String inFileName) {
		File inFile = new File(inFileName);

		if (!inFile.exists()) {
			System.err.println("No such file: " + inFileName);
			return null;
		} else if (inFile.isDirectory()) {
			System.err.println(inFileName
					+ " is a directory, but must be a file");
			return null;
		}

		long filesize = inFile.length();

		StringBuffer fileData = new StringBuffer();
		long totalRead = 0L;
		try {
			filesize = inFile.length();
			BufferedReader reader = new BufferedReader(new FileReader(inFile));
			char[] buf = new char[FILE_READ_BYTESIZE];

			int numRead = 0;
			while ((numRead = reader.read(buf)) != -1) {
				totalRead += numRead;
				String readData = String.valueOf(buf, 0, numRead);
				fileData.append(readData);
				buf = new char[FILE_READ_BYTESIZE];
			}
			// The reported size is often longer than the real one, but never
			// more than the the buffer size
			if (totalRead + FILE_READ_BYTESIZE < filesize) {
				System.err.print("File read error, reported size is ");
				System.err.print(filesize);
				System.err.print(" but only read ");
				System.err.println(totalRead);
			} else {
				System.out.print("File " + inFileName + " read:");
				System.out.println(totalRead);
			}
			reader.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return fileData.toString();
	}

	public String fill(Document template, JsonObject model) {

		if (template == null || model == null) {
			return null;
		}

		// Parse the document element here
		Element docEle = template.getDocumentElement();
		return this.fill(docEle, model);
	}

	public String fill(Element instance, JsonObject model) {
		// Capture the document
		this.doc = instance.getOwnerDocument();
		// Work goes here
		this.parseElement(instance, model, new ArrayList<String>());
		// String
		return this.elementToString(instance);
	}

	public String fill(String templateString, String modelString) {

		if (templateString == null || modelString == null) {
			return null;
		}

		doc = this.loadDocument(templateString);
		JsonElement model = this.loadModel(modelString);

		return this.fill(doc, model.getAsJsonObject());

	}

	/**
	 * Fills repeating elements. Tick here: copy the node and append the clones
	 * 
	 * @param instance
	 * @param model
	 */
	private void fillRepeat(Element instance, JsonArray model) {

		// We run through the clone and add all the child elements
		Element clone = (Element) instance.cloneNode(true);

		boolean firstLine = true;
		
		for (JsonElement member : model) {
			if (firstLine) {
				this.fillValues(instance, member);
				firstLine = false;
			} else {
				// Subsequent lines duplicate the content of the
				// original element. To make that work that element
				// needs to be temporarily part of the DOM tree
				// otherwise documentFragment will fail
				Element workclone = (Element) clone.cloneNode(true);
				instance.appendChild(workclone);
				this.fillValues(workclone, member);
				// Move the workclone's children
				while (workclone.hasChildNodes()) {
					instance.appendChild(workclone.removeChild(workclone
							.getFirstChild()));
				}
				// We remove it
				instance.removeChild(workclone);
			}
		}
	}

	private void fillTextElement(Element instance, String value) {

		// It is a string, boolean or number and becomes the inner
		// part of the current element - special case: an element with
		// the class listElement or one element inside (a span)

		// If there are no children we just add the content
		if (!instance.hasChildNodes()) {
			instance.appendChild(this.getFragment(value));
		} else {

			// with children we need to check if we have a listElement or
			// a first child
			Element firstChild = null;
			NodeList children = instance.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node curNode = children.item(i);
				if (curNode.getNodeType() == Node.ELEMENT_NODE) {
					Element curElement = (Element) curNode;
					if (firstChild == null) {
						firstChild = curElement;
					}
					if (curElement.hasAttribute("class")) {
						String classvalue = curElement.getAttribute("class");
						if (classvalue.contains("listElement")) {
							firstChild = curElement;
							break; // We found where we add the stuff
						}
					}
				}
			}

			// If we found an element we append it there, else we add it to the
			// top
			if (firstChild == null) {
				this.dropChildren(instance);
				instance.appendChild(this.getFragment(value));
			} else {
				this.dropChildren(firstChild);
				firstChild.appendChild(this.getFragment(value));
			}

		}

		// handle special case of input fields - needs to have the value
		// attribute set
		if (instance.getNodeName().equalsIgnoreCase("input")) {
			instance.setAttribute("value", value);
		}
	}

	private void fillValues(Element instance, JsonElement model) {

		// based on the JSON element type
		// if it is an JS array or we have a "fill the content" repeated
		// if it is an JS object it is fill the details
		if (model.isJsonObject()) {
			// This is a JSON object and every key needs to be applied to
			this.parseElement(instance, model.getAsJsonObject(), new ArrayList<String>());

		} else if (model.isJsonArray()) {
			// This is a JavaScript array and needs to repeat the content of
			// the current element for each array member
			this.fillRepeat(instance, model.getAsJsonArray());

		} else {
			// fill in the text
			this.fillTextElement(instance, model.getAsString());
		}
	}

	/**
	 * Creates a document fragment that in the easiest case might be just a
	 * string but could be a HTML snippet
	 * 
	 * @param string
	 *            - the markup to insert
	 * @return the fragment
	 */
	private DocumentFragment getFragment(String fragmentSource) {
		// Wrap the fragment in an arbitrary element
		String fragment = "<fragment>" + fragmentSource + "</fragment>";
		try {
			// Create a DOM builder and parse the fragment
			DocumentBuilderFactory factory = DocumentBuilderFactory
					.newInstance();
			InputSource source = new InputSource(new StringReader(fragment));
			Document d = factory.newDocumentBuilder().parse(source);

			// Import the nodes of the new document into doc so that they
			// will be compatible with doc
			Node node = doc.importNode(d.getDocumentElement(), true);

			// Create the document fragment node to hold the new nodes
			DocumentFragment docfrag = doc.createDocumentFragment();

			// Move the nodes into the fragment
			while (node.hasChildNodes()) {
				docfrag.appendChild(node.removeChild(node.getFirstChild()));
			}

			// Return the fragment
			return docfrag;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	private Document loadDocument(String templateString) {

		Document d = null;

		if (templateString != null) {

			DocumentBuilderFactory factory = DocumentBuilderFactory
					.newInstance();
			factory.setValidating(false); // Will blow if set to true
			factory.setNamespaceAware(true);

			InputSource source = new InputSource(new StringReader(
					templateString));

			try {
				DocumentBuilder docb = factory.newDocumentBuilder();
				d = docb.parse(source);

			} catch (Exception e) {
				e.printStackTrace();
				d = null;
			}

			if (d == null) {
				System.out.println("DOM generation failed:\n" + templateString);
			}

		}

		return d;
	}

	private JsonElement loadModel(String modelString) {
		JsonParser p = new JsonParser();
		JsonElement result = null;
		try {
			result = p.parse(modelString);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	/**
	 * Process an element and apply
	 * 
	 * @param curElement
	 *            Element to be filled
	 * @param model
	 *            JSON Object with all replacement elements
	 */
	private void parseElement(Element curElement, JsonObject model,
			List<String> processedElements) {

		for (Entry<String, JsonElement> curEntry : model.entrySet()) {
	
			String curKey = curEntry.getKey();

			if (!processedElements.contains(curKey)) {

				// if a model key starts with _ it is an attribute
				// to be added to the current element
				if (curKey.startsWith("_")) {

					String attName = curKey.substring(1);
					curElement.setAttribute(attName, curEntry.getValue().getAsString());
					processedElements.add(curKey);

				} else if (this.elementMatcher(curElement, curKey)) {
					// We found a match and need to handle it
					this.fillValues(curElement, curEntry.getValue());
					processedElements.add(curKey);
				} else {
					// We parse the child elements
					NodeList allTags = curElement.getChildNodes();
					for (int i = 0; i < allTags.getLength(); i++) {
						Node curNode = allTags.item(i);
						if (curNode.getNodeType() == Node.ELEMENT_NODE) {
							Element childElement = (Element) curNode;
							this.parseElement(childElement, model,
									processedElements);
						}
					}
				}				
			}
		}
	}

	public void runTestCases()  {
		
		String template = "<div><h1 class=\"title\"></h1></div>";
		String data = "{\"title\" : \"<b>Hello world!</b>\"}";

		System.out.println(this.fill(template, data));

		template = "<div id=\"container\">\n";
		template += "  <div id=\"hello\"></div>\n";
		template += "  <div class=\"goodbye\"></div>\n";
		template += "  <span></span>\n";
		template += "		  <input type=\"text\" name=\"greeting\" />\n";
		template += "  <button class=\"hi-button\" data-bind=\"hi-label\"></button>\n";
		template += "</div>";

		data = "{\"_class\" :     \"message\",";
		data += "\"hello\" :      \"Hello\",";
		data += "\"goodbye\" :    \"Goodbye!\",";
		data += "\"span\" :       \"<i>See Ya!</i>\",";
		data += "\"greeting\" :   \"Howdy!\",";
		data += "\"hi-label\" : \"Terve!\",";
		data += "\"stuff\" : \"Blah\"";
		data += "}";

		System.out.println(this.fill(template, data));
		
		template = "<ul id=\"activities\">\n";
		template += "  <li class=\"activity\"></li>";
		template += "</ul>";
		
		data = "{activities : [";
		data += "  {activity: 'Jogging'},\n";
		data += "  {activity: 'Gym'},\n";
		data += "  {activity: 'Sky Diving'}\n";
		data += "]}";
		
		System.out.println(this.fill(template, data));
		
	}

}