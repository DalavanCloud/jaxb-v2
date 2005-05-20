/*
 * Copyright 2004 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.sun.tools.xjc.reader.dtd.bindinfo;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.ValidatorHandler;

import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JPackage;
import com.sun.tools.xjc.AbortException;
import com.sun.tools.xjc.ErrorReceiver;
import com.sun.tools.xjc.SchemaCache;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.reader.Const;
import com.sun.tools.xjc.util.CodeModelClassFactory;
import com.sun.tools.xjc.util.ErrorReceiverFilter;
import com.sun.xml.bind.v2.runtime.ForkContentHandler;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * Root of the binding information.
 */
public class BindInfo
{
    /** Controller object that can be used to report errors. */
    protected final ErrorReceiver errorReceiver;

    /*package*/ final Model model;

    /**
     * The -p option that should control the default Java package that
     * will contain the generated code. Null if unspecified. This takes
     * precedence over the value specified in the binding file.
     */
    private final String defaultPackage;
    
    public BindInfo(Model model, InputSource source, ErrorReceiver _errorReceiver) throws AbortException {
        
        this( model, parse(source,_errorReceiver), _errorReceiver);
    }
    
    public BindInfo(Model model, Document _dom, ErrorReceiver _errorReceiver) {
        this.model = model;
        this.dom = _dom.getDocumentElement();
        this.codeModel = model.codeModel;
        this.errorReceiver = _errorReceiver;
        this.classFactory = new CodeModelClassFactory(_errorReceiver);
        // TODO: decide name converter from the binding file

        this.defaultPackage = model.options.defaultPackage;
        
        // process element declarations
        for( Element ele : DOMUtil.getChildElements(dom,"element")) {
            BIElement e = new BIElement(this,ele);
            elements.put(e.name(),e);
        }

        // add built-in conversions
        BIUserConversion.addBuiltinConversions(this,conversions);
        
        // process conversion declarations
        for( Element cnv : DOMUtil.getChildElements(dom,"conversion")) {
            BIConversion c = new BIUserConversion(this,cnv);
            conversions.put(c.name(),c);
        }
        for( Element en : DOMUtil.getChildElements(dom,"enumeration")) {
            BIConversion c = BIEnumeration.create( en, this );
            conversions.put(c.name(),c);
        }
        // TODO: check the uniquness of conversion name
        
        
        // process interface definitions
        for( Element itf : DOMUtil.getChildElements(dom,"interface")) {
            BIInterface c = new BIInterface(itf);
            interfaces.put(c.name(),c);
        }
    }
    
    
    /** CodeModel object that is used by this binding file. */
    final JCodeModel codeModel;
    
    /** Wrap the codeModel object and automate error reporting. */
    final CodeModelClassFactory classFactory;

    /** DOM tree that represents binding info. */
    private final Element dom;

    /** Conversion declarations. */
    private final Map<String,BIConversion> conversions = new HashMap<String,BIConversion>();

    /** Element declarations keyed by names. */
    private final Map<String,BIElement> elements = new HashMap<String,BIElement>();
    
    /** interface declarations keyed by names. */
    private final Map<String,BIInterface> interfaces = new HashMap<String,BIInterface>();
  
    
    /** XJC extension namespace. */
    private static final String XJC_NS = Const.XJC_EXTENSION_URI;
    
//
//
//    Exposed public methods
//
//
    /** Gets the serialVersionUID if it's turned on. */
    public Long getSerialVersionUID() {
        Element serial = DOMUtil.getElement(dom,XJC_NS,"serializable");
        if(serial==null)    return null;

        String v = DOMUtil.getAttribute(serial,"uid");
        if(v==null) v="1";
        return new Long(v);
    }
    
    /** Gets the xjc:superClass customization if it's turned on. */
    public JClass getSuperClass() {
        Element sc = DOMUtil.getElement(dom,XJC_NS,"superClass");
        if (sc == null) return null;

        JDefinedClass c;

        try {
            String v = DOMUtil.getAttribute(sc,"name");
            if(v==null)     return null;
            c = codeModel._class(v);
            c.hide();
        } catch (JClassAlreadyExistsException e) {
            c = e.getExistingClass();
        }

        return c;
    }

    /** Gets the xjc:superInterface customization if it's turned on. */
    public JClass getSuperInterface() {
        Element sc = DOMUtil.getElement(dom,XJC_NS,"superInterface");
        if (sc == null) return null;

        String name = DOMUtil.getAttribute(sc,"name");
        if (name == null) return null;

        JDefinedClass c;

        try {
            c = codeModel._class(name, ClassType.INTERFACE);
            c.hide();
        } catch (JClassAlreadyExistsException e) {
            c = e.getExistingClass();
        }

        return c;
    }

    /** Gets the specified package name (options/@package). */
    public JPackage getTargetPackage() {
        String p;
        if( defaultPackage!=null )
            p = defaultPackage;
        else
            p = getOption("package", "");
        return codeModel._package(p);
    }

    /**
     * Gets the conversion declaration from the binding info.
     * 
     * @return
     *        A non-null valid BIConversion object.
     */
    public BIConversion conversion(String name) {
        BIConversion r = conversions.get(name);
        if (r == null)
            throw new AssertionError("undefined conversion name: this should be checked by the validator before we read it");
        return r;
    }
    
    /**
     * Gets the element declaration from the binding info.
     * 
     * @return
     *        If there is no declaration with a given name,
     *        this method returns null.
     */
    public BIElement element( String name ) {
        return elements.get(name);
    }
    /** Iterates all {@link BIElement}s in a read-only set. */
    public Collection<BIElement> elements() {
        return elements.values();
    }
    
    /** Returns all {@link BIInterface}s in a read-only set. */
    public Collection<BIInterface> interfaces() {
        return interfaces.values();
    }
    
    
    
//
//
//    Internal utility methods
//
//
    
    
    /** Gets the value from the option element. */
    private String getOption(String attName, String defaultValue) {
        Element opt = DOMUtil.getElement(dom,"options");
        if (opt != null) {
            String s = DOMUtil.getAttribute(opt,attName);
            if (s != null)
                return s;
        }
        return defaultValue;
    }

    /**
     * Lazily parsed schema for the binding file.
     */
    private static SchemaCache bindingFileSchema = new SchemaCache(BindInfo.class.getResource("bindingfile.xsd"));

    /**
     * Parses an InputSource into dom4j Document.
     * Returns null in case of an exception.
     */
    private static Document parse( InputSource is, ErrorReceiver receiver ) throws AbortException {
        try {
            ValidatorHandler validator = bindingFileSchema.newValidator();

            // set up the pipe line as :
            //   parser->validator->factory
            SAXParserFactory pf = SAXParserFactory.newInstance();
            pf.setNamespaceAware(true);
            DOMBuilder builder = new DOMBuilder();

            ErrorReceiverFilter controller = new ErrorReceiverFilter(receiver);
            validator.setErrorHandler(controller);
            XMLReader reader = pf.newSAXParser().getXMLReader();
            reader.setErrorHandler(controller);
            reader.setContentHandler(new ForkContentHandler(validator,builder));

            reader.parse(is);
            
            if(controller.hadError())   throw new AbortException();
            return (Document)builder.getDOM();
        } catch( IOException e ) {
            receiver.error( new SAXParseException(e.getMessage(),null,e) );
        } catch( SAXException e ) {
            receiver.error( new SAXParseException(e.getMessage(),null,e) );
        } catch( ParserConfigurationException e ) {
            receiver.error( new SAXParseException(e.getMessage(),null,e) );
        }
        
        throw new AbortException();
    }
}
