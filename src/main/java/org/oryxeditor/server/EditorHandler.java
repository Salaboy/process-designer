/***************************************
 * Copyright (c) 2008-2010
 * Philipp Berger 2009
 * Intalio, Inc 2010
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
****************************************/

package org.oryxeditor.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.StringTokenizer;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.intalio.web.plugin.IDiagramPluginService;
import com.intalio.web.plugin.impl.PluginServiceImpl;
import com.intalio.web.profile.IDiagramProfile;
import com.intalio.web.profile.IDiagramProfileService;
import com.intalio.web.profile.impl.ProfileServiceImpl;

/**
 * Servlet to load plugin and Oryx stencilset
 */
public class EditorHandler extends HttpServlet {

    private static final long serialVersionUID = -7439613152623067053L;

    /**
     * da logger
     */
    private static final Logger _logger = 
        Logger.getLogger(EditorHandler.class);
    
    /**
     * The base path under which the application will be made available at runtime.
     * This constant should be used throughout the application.
     */
	public static final String oryx_path = "/designer/";
	
	/**
	 * The designer DEV flag.
	 * When set, the logging will be enabled at the javascript level
	 */
	public static final String DEV = "designer.dev";
	
	/**
	 * The profile service, a global registry to get the
	 * profiles.
	 */
    private IDiagramProfileService _profileService = null;
    
    /**
     * The plugin service, a global registry for all plugins.
     */
    private IDiagramPluginService _pluginService = null;
    
    /**
     * editor.html document.
     */
    private Document _doc = null;		
    
	public void init(ServletConfig config) throws ServletException {
        super.init(config);
        _profileService = ProfileServiceImpl.getInstance(
                config.getServletContext());
        _pluginService = PluginServiceImpl.getInstance(
                config.getServletContext());
        
        String editor_file = config.
            getServletContext().getRealPath("/editor.html");
        try {
            _doc = readDocument(editor_file);
        } catch (Exception e) {
            throw new ServletException(
                    "Error while parsing editor.html", e);
        }
        if (_doc == null) {
            _logger.error("Invalid editor.html, " +
            		"could not be read as a document.");
            throw new ServletException("Invalid editor.html, " +
            		"could not be read as a document.");
        }
        
        Element root = _doc.getRootElement();
        Element head = root.getChild("head", root.getNamespace());
        if (head == null) {
            _logger.error("Invalid editor.html. No html or head tag");
            throw new ServletException("Invalid editor.html. " +
            		"No html or head tag");
        }
    }

	protected void doGet(HttpServletRequest request, 
	        HttpServletResponse response) 
	        throws ServletException, IOException {
	    Document doc = (Document) _doc.clone();
	    String profileName = request.getParameter("profile");
	    IDiagramProfile profile = _profileService.findProfile(
	            request, profileName);
	    if (profile == null) {
	    	_logger.error("No profile with the name " + profileName 
	    	        + " was registered");
	    	throw new IllegalArgumentException(
	    	        "No profile with the name " + profileName + 
	    	            " was registered");
	    }

        // generate the script for each file in js_files.json 
		try {
		    JSONObject obj = new JSONObject(readEnvFiles());
		
			JSONArray array = obj.getJSONArray("files");
			for (int i = 0 ; i < array.length() ; i++) {
				addscript(doc, oryx_path + array.getString(i), true);
			}
		} catch (JSONException e) {
			_logger.error("invalid js_files.json");
			_logger.error(e.getMessage(), e);
			throw new RuntimeException("Error initializing the " +
					"environment of the editor");
		}
		
	    // generate script to setup the languages
	    addscript(doc, oryx_path + "i18n/translation_en_us.js", 
	            true);
	    
	    // generate script tags for plugins.
	    // they are located after the initialization script.
	    for (String plugin : profile.getPlugins()) {
	        _pluginService.findPlugin(request, plugin);
	        addscript(doc, oryx_path + "plugin/" + plugin + ".js", 
	                false);
	    }

	    // send the updated editor.html to client 
	    response.setContentType("application/xhtml+xml");
	    XMLOutputter outputter = new XMLOutputter();
	    Format format = Format.getPrettyFormat();
	    format.setExpandEmptyElements(true);
	    outputter.setFormat(format);
	    String html = outputter.outputString(doc);
	    StringTokenizer tokenizer = new StringTokenizer(
	            html, "@", true);
	    StringBuilder resultHtml = new StringBuilder();
	    boolean tokenFound = false;
	    boolean replacementMade = false;
	    while(tokenizer.hasMoreTokens()) {
	        String elt = tokenizer.nextToken();
	        if ("title".equals(elt)) {
	            resultHtml.append(profile.getTitle());
	            replacementMade = true;
	        } else if ("stencilset".equals(elt)) {
	            resultHtml.append(profile.getStencilSet());
	            replacementMade = true;
	        } else if ("debug".equals(elt)) {
	            resultHtml.append(System.getProperty(DEV) != null);
	            replacementMade = true;
	        } else if ("profileplugins".equals(elt)) {
	            StringBuilder plugins = new StringBuilder();
	            boolean commaNeeded = false;
	            for (String ext : profile.getPlugins()) {
	                if (commaNeeded) {
	                    plugins.append(",");
	                } else {
	                    commaNeeded = true;
	                }
	                plugins.append("\"").append(ext).append("\"");
	            }
	            resultHtml.append(plugins.toString());
	            replacementMade = true;
	        } else if ("ssextensions".equals(elt)) {
	            StringBuilder ssexts = new StringBuilder();
	            boolean commaNeeded = false;
	            for (String ext : profile.getStencilSetExtensions()) {
	                if (commaNeeded) {
	                    ssexts.append(",");
	                } else {
	                    commaNeeded = true;
	                }
	                ssexts.append("\"").append(ext).append("\"");
	            }
	            resultHtml.append(ssexts.toString());
	            replacementMade = true;
	        } else if ("@".equals(elt)) {
	            if (replacementMade) {
	                tokenFound = false;
	                replacementMade = false;
	            } else {
	                tokenFound = true;
	            }
	        } else {
	            if (tokenFound) {
	                tokenFound = false;
	                resultHtml.append("@");
	            }
	            resultHtml.append(elt);
	        }
	    }

	    response.getWriter().write(resultHtml.toString());
	}
	
	/**
	 * Reads the document from the file at the given path
	 * @param path the path to the file
	 * @return a document
	 * @throws JDOMException
	 * @throws IOException
	 */
	private static Document readDocument(String path) 
	    throws JDOMException, IOException {
	    SAXBuilder builder = new SAXBuilder(false); 

	    // no DTD validation
	    builder.setValidation(false);
	    builder.setFeature("http://xml.org/sax/features/validation", false);
	    builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
	    builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

	    Document anotherDocument = builder.build(new File(path));
	    return anotherDocument;
    }
	
	/**
	 * Adds a script to the head.
	 * @param doc the document to use.
	 * @param src the location of the script
	 */
	private void addscript(Document doc, String src, boolean isCore) {
	    Namespace nm = doc.getRootElement().getNamespace();
        Element script = new Element("script", nm);
        // set the attributes
        script.setAttribute("src", src);
        script.setAttribute("type", "text/javascript");
        //add an empty text node in them
        script.addContent("");
        // put it to the right place
        Element head = doc.getRootElement().getChild("head", nm);
        
        if (isCore) {
            // then place it first.
          //insert before the last script tag.
            head.addContent(head.getContentSize() -2, script);
        } else {
            head.addContent(script);
        }
        

		return;
	}
	
	/**
	 * @return read the files to be placed as core scripts
	 * from a configuration file in a json file.
	 * @throws IOException 
	 */
	private String readEnvFiles() throws IOException {
	    FileInputStream core_scripts = new FileInputStream(
	            getServletContext().getRealPath("/js/js_files.json"));
        
        try {
            ByteArrayOutputStream stream = 
                new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = core_scripts.read(buffer)) != -1) {
                stream.write(buffer, 0, read);
            }
            return stream.toString();
        } finally {
            try {
                core_scripts.close();
            } catch (IOException e) {
                _logger.error(e.getMessage(), e);
            }
        }
	}
}