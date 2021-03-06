/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.xml.bind.v2.runtime.output;

import com.sun.xml.bind.marshaller.NoEscapeHandler;
import com.sun.xml.bind.v2.runtime.JAXBContextImpl;
import com.sun.xml.bind.v2.runtime.Name;
import com.sun.xml.bind.v2.runtime.XMLSerializer;
import com.sun.xml.bind.v2.runtime.unmarshaller.Base64Data;
import com.sun.xml.fastinfoset.EncodingConstants;
import com.sun.xml.fastinfoset.stax.StAXDocumentSerializer;
import org.jvnet.fastinfoset.VocabularyApplicationData;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBContext;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link XmlOutput} for {@link StAXDocumentSerializer}.
 * <p>
 * This class is responsible for managing the indexing of elements, attributes
 * and local names that are known to JAXB by way of the JAXBContext (generated
 * from JAXB beans or schema). The pre-encoded UTF-8 representations of known
 * local names are also utilized.
 * <p>
 * The lookup of  elements, attributes and local names with respect to a context
 * is very efficient. It relies on an incrementing base line so that look up is
 * performed in O(1) time and only uses static memory. When the base line reaches
 * a point where integer overflow will occur the arrays and base line are reset
 * (such an event is rare and will have little impact on performance).
 * <p>
 * A weak map of JAXB contexts to optimized tables for attributes, elements and
 * local names is utilized and stored on the LowLevel StAX serializer. Thus,
 * optimized serializing can work other multiple serializing of JAXB beans using
 * the same LowLevel StAX serializer instance. This approach works best when JAXB
 * contexts are only created once per schema or JAXB beans (which is the recommended
 * practice as the creation JAXB contexts are expensive, they are thread safe and
 * can be reused).
 *
 * @author Paul.Sandoz@Sun.Com
 */
public final class FastInfosetStreamWriterOutput extends XMLStreamWriterOutput {
    private final StAXDocumentSerializer fiout;
    private final Encoded[] localNames;
    private final TablesPerJAXBContext tables;
    
    /**
     * Holder for the optimzed element, attribute and
     * local name tables.
     */
    final static class TablesPerJAXBContext {
        final int[] elementIndexes;
        final int[] elementIndexPrefixes;
        
        final int[] attributeIndexes;
        final int[] localNameIndexes;

        /**
         * The offset of the index
         */
        int indexOffset;
        
        /**
         * The the maximum known value of an index
         */
        int maxIndex;
        
        /**
         * True if the tables require clearing
         */
        boolean requiresClear;
        
        /**
         * Create a new set of tables for a JAXB context.
         * <p>
         * @param context the JAXB context.
         * @param initialIndexOffset the initial index offset to calculate
         *                           the maximum possible index
         *
         */
        TablesPerJAXBContext(JAXBContextImpl context, int initialIndexOffset) {
            elementIndexes = new int[context.getNumberOfElementNames()];
            elementIndexPrefixes = new int[context.getNumberOfElementNames()];
            attributeIndexes = new int[context.getNumberOfAttributeNames()];
            localNameIndexes = new int[context.getNumberOfLocalNames()];
            
            indexOffset = 1;
            maxIndex = initialIndexOffset + elementIndexes.length + attributeIndexes.length;
        }

        /**
         * Require that tables are cleared.
         */
        public void requireClearTables() {
            requiresClear = true;
        }
        
        /**
         * Clear or reset the tables.
         * <p>
         * @param intialIndexOffset the initial index offset to calculate
         *                           the maximum possible index
         */
        public void clearOrResetTables(int intialIndexOffset) {
            if (requiresClear) {
                requiresClear = false;

                // Increment offset to new position
                indexOffset += maxIndex;
                // Reset the maximum known value of an index
                maxIndex = intialIndexOffset + elementIndexes.length + attributeIndexes.length;
                // Check if there is enough free space
                // If overflow
                if ((indexOffset + maxIndex) < 0) {
                    clearAll();
                }
            } else {
                // Reset the maximum known value of an index
                maxIndex = intialIndexOffset + elementIndexes.length + attributeIndexes.length;
                // Check if there is enough free space
                // If overflow
                if ((indexOffset + maxIndex) < 0) {
                    resetAll();
                }
            }
        }
        
        private void clearAll() {
            clear(elementIndexes);
            clear(attributeIndexes);
            clear(localNameIndexes);
            indexOffset = 1;
        }
        
        private void clear(int[] array) {
            for (int i = 0; i < array.length; i++) {
                array[i] = 0;
            }
        }
        
        /**
         * Increment the maximum know index value
         * <p>
         * The indexes are preserved.
         */
        public void incrementMaxIndexValue() {
            // Increment the maximum value of an index
            maxIndex++;
            // Check if there is enough free space
            // If overflow
            if ((indexOffset + maxIndex) < 0) {
                resetAll();
            }
        }
                
        private void resetAll() {
            clear(elementIndexes);
            clear(attributeIndexes);
            clear(localNameIndexes);
            indexOffset = 1;
        }
        
        private void reset(int[] array) {
            for (int i = 0; i < array.length; i++) {
                if (array[i] > indexOffset) {
                    array[i] = array[i] - indexOffset + 1;
                } else {
                    array[i] = 0;
                }
            }
        }
        
    }

    /**
     * Holder of JAXB contexts -> tables.
     * <p>
     * An instance will be registered with the 
     * {@link StAXDocumentSerializer}.
     */
    final static class AppData implements VocabularyApplicationData {
        final Map<JAXBContext, TablesPerJAXBContext> contexts =
                new WeakHashMap<JAXBContext, TablesPerJAXBContext>();
        final Collection<TablesPerJAXBContext> collectionOfContexts = contexts.values();

        /**
         * Clear all the tables.
         */
        public void clear() {
            for(TablesPerJAXBContext c : collectionOfContexts)
                c.requireClearTables();
        }
    }
    
    public FastInfosetStreamWriterOutput(StAXDocumentSerializer out,
            JAXBContextImpl context) {
        super(out, NoEscapeHandler.theInstance);
        
        this.fiout = out;
        this.localNames = context.getUTF8NameTable();
        
        final VocabularyApplicationData vocabAppData = fiout.getVocabularyApplicationData();
        AppData appData = null;
        if (vocabAppData == null || !(vocabAppData instanceof AppData)) {
            appData = new AppData();
            fiout.setVocabularyApplicationData(appData);
        } else {
            appData = (AppData)vocabAppData;
        }
        
        final TablesPerJAXBContext tablesPerContext = appData.contexts.get(context);
        if (tablesPerContext != null) {
            tables = tablesPerContext;
            /**
             * Obtain the current local name index. Thus will be used to
             * calculate the maximum index value when serializing for this context
             */
            tables.clearOrResetTables(out.getLocalNameIndex());
        } else {
            tables = new TablesPerJAXBContext(context, out.getLocalNameIndex());
            appData.contexts.put(context, tables);
        }
    }
    
    @Override
    public void startDocument(XMLSerializer serializer, boolean fragment, 
            int[] nsUriIndex2prefixIndex, NamespaceContextImpl nsContext) 
            throws IOException, SAXException, XMLStreamException {
        super.startDocument(serializer, fragment, nsUriIndex2prefixIndex, nsContext);
        
        if (fragment)
            fiout.initiateLowLevelWriting();
    }
    
    @Override
    public void endDocument(boolean fragment) throws IOException, SAXException, XMLStreamException {
        super.endDocument(fragment);
    }
    
    @Override
    public void beginStartTag(Name name) throws IOException {
        fiout.writeLowLevelTerminationAndMark();
        
        if (nsContext.getCurrent().count() == 0) {
            final int qNameIndex = tables.elementIndexes[name.qNameIndex] - tables.indexOffset;
            final int prefixIndex = nsUriIndex2prefixIndex[name.nsUriIndex];
            
            if (qNameIndex >= 0 && 
                    tables.elementIndexPrefixes[name.qNameIndex] == prefixIndex) {
                fiout.writeLowLevelStartElementIndexed(EncodingConstants.ELEMENT, qNameIndex);
            } else {
                tables.elementIndexes[name.qNameIndex] = fiout.getNextElementIndex() + tables.indexOffset;
                tables.elementIndexPrefixes[name.qNameIndex] = prefixIndex;                
                writeLiteral(EncodingConstants.ELEMENT | EncodingConstants.ELEMENT_LITERAL_QNAME_FLAG,
                        name,
                        nsContext.getPrefix(prefixIndex),
                        nsContext.getNamespaceURI(prefixIndex));
            }
        } else {
            beginStartTagWithNamespaces(name);
        }
    }
    
    public void beginStartTagWithNamespaces(Name name) throws IOException {
        final NamespaceContextImpl.Element nse = nsContext.getCurrent();
        
        fiout.writeLowLevelStartNamespaces();
        for (int i = nse.count() - 1; i >= 0; i--) {
            final String uri = nse.getNsUri(i);
            if (uri.length() == 0 && nse.getBase() == 1)
                continue;   // no point in definint xmlns='' on the root
            fiout.writeLowLevelNamespace(nse.getPrefix(i), uri);
        }
        fiout.writeLowLevelEndNamespaces();
        
        final int qNameIndex = tables.elementIndexes[name.qNameIndex] - tables.indexOffset;
        final int prefixIndex = nsUriIndex2prefixIndex[name.nsUriIndex];
        
        if (qNameIndex >= 0 &&
                tables.elementIndexPrefixes[name.qNameIndex] == prefixIndex) {
            fiout.writeLowLevelStartElementIndexed(0, qNameIndex);
        } else {
            tables.elementIndexes[name.qNameIndex] = fiout.getNextElementIndex() + tables.indexOffset;
            tables.elementIndexPrefixes[name.qNameIndex] = prefixIndex;
            writeLiteral(EncodingConstants.ELEMENT_LITERAL_QNAME_FLAG,
                    name,
                    nsContext.getPrefix(prefixIndex),
                    nsContext.getNamespaceURI(prefixIndex));
        }
    }
    
    @Override
    public void attribute(Name name, String value) throws IOException {
        fiout.writeLowLevelStartAttributes();
        
        final int qNameIndex = tables.attributeIndexes[name.qNameIndex] - tables.indexOffset;
        if (qNameIndex >= 0) {
            fiout.writeLowLevelAttributeIndexed(qNameIndex);
        } else {
            tables.attributeIndexes[name.qNameIndex] = fiout.getNextAttributeIndex() + tables.indexOffset;
            
            final int namespaceURIId = name.nsUriIndex;
            if (namespaceURIId == -1) {
                writeLiteral(EncodingConstants.ATTRIBUTE_LITERAL_QNAME_FLAG,
                        name,
                        "",
                        "");
            } else {
                final int prefix = nsUriIndex2prefixIndex[namespaceURIId];
                writeLiteral(EncodingConstants.ATTRIBUTE_LITERAL_QNAME_FLAG,
                        name,
                        nsContext.getPrefix(prefix),
                        nsContext.getNamespaceURI(prefix));
            }
        }
        
        fiout.writeLowLevelAttributeValue(value);
    }
    
    private void writeLiteral(int type, Name name, String prefix, String namespaceURI) throws IOException {
        final int localNameIndex = tables.localNameIndexes[name.localNameIndex] - tables.indexOffset;
        
        if (localNameIndex < 0) {
            tables.localNameIndexes[name.localNameIndex] = fiout.getNextLocalNameIndex() + tables.indexOffset;
            
            fiout.writeLowLevelStartNameLiteral(
                    type,
                    prefix,
                    localNames[name.localNameIndex].buf,
                    namespaceURI);
        } else {
            fiout.writeLowLevelStartNameLiteral(
                    type,
                    prefix,
                    localNameIndex,
                    namespaceURI);
        }
    }
    
    @Override
    public void endStartTag() throws IOException {
        fiout.writeLowLevelEndStartElement();
    }
    
    @Override
    public void endTag(Name name) throws IOException {
        fiout.writeLowLevelEndElement();
    }
    
    @Override
    public void endTag(int prefix, String localName) throws IOException {
        fiout.writeLowLevelEndElement();
    }
    
    
    @Override
    public void text(Pcdata value, boolean needsSeparatingWhitespace) throws IOException {
        if (needsSeparatingWhitespace)
            fiout.writeLowLevelText(" ");
        
        /*
         * Check if the CharSequence is from a base64Binary data type
         */
        if (!(value instanceof Base64Data)) {
            final int len = value.length();
            if(len <buf.length) {
                value.writeTo(buf, 0);
                fiout.writeLowLevelText(buf, len);
            } else {
                fiout.writeLowLevelText(value.toString());
            }
        } else {
            final Base64Data dataValue = (Base64Data)value;
            // Write out the octets using the base64 encoding algorithm
            fiout.writeLowLevelOctets(dataValue.get(), dataValue.getDataLen());
        }
    }
    
    
    @Override
    public void text(String value, boolean needsSeparatingWhitespace) throws IOException {
        if (needsSeparatingWhitespace)
            fiout.writeLowLevelText(" ");
        
        fiout.writeLowLevelText(value);
    }
    
    
    @Override
    public void beginStartTag(int prefix, String localName) throws IOException {
        fiout.writeLowLevelTerminationAndMark();
        
        int type = EncodingConstants.ELEMENT;
        if (nsContext.getCurrent().count() > 0) {
            final NamespaceContextImpl.Element nse = nsContext.getCurrent();
            
            fiout.writeLowLevelStartNamespaces();
            for (int i = nse.count() - 1; i >= 0; i--) {
                final String uri = nse.getNsUri(i);
                if (uri.length() == 0 && nse.getBase() == 1)
                    continue;   // no point in definint xmlns='' on the root
                fiout.writeLowLevelNamespace(nse.getPrefix(i), uri);
            }
            fiout.writeLowLevelEndNamespaces();
            
            type= 0;
        }
        
        final boolean isIndexed = fiout.writeLowLevelStartElement(
                type,
                nsContext.getPrefix(prefix),
                localName,
                nsContext.getNamespaceURI(prefix));

        if (!isIndexed)
            tables.incrementMaxIndexValue();
    }
    
    @Override
    public void attribute(int prefix, String localName, String value) throws IOException {
        fiout.writeLowLevelStartAttributes();

        boolean isIndexed; 
        if (prefix == -1)
            isIndexed = fiout.writeLowLevelAttribute("", "", localName);
        else
            isIndexed = fiout.writeLowLevelAttribute(
                    nsContext.getPrefix(prefix),
                    nsContext.getNamespaceURI(prefix),
                    localName);
        
        if (!isIndexed)
            tables.incrementMaxIndexValue();
        
        fiout.writeLowLevelAttributeValue(value);
    }
}
