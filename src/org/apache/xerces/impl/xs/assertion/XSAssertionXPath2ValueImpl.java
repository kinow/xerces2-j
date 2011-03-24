/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.xerces.impl.xs.assertion;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.xerces.dom.PSVIElementNSImpl;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.impl.dv.XSSimpleType;
import org.apache.xerces.impl.xs.SchemaSymbols;
import org.apache.xerces.impl.xs.util.XSTypeHelper;
import org.apache.xerces.util.XMLChar;
import org.apache.xerces.xs.ElementPSVI;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSObjectList;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTypeDefinition;
import org.eclipse.wst.xml.xpath2.processor.DynamicContext;
import org.eclipse.wst.xml.xpath2.processor.PsychoPathTypeHelper;
import org.eclipse.wst.xml.xpath2.processor.internal.types.AnyAtomicType;
import org.eclipse.wst.xml.xpath2.processor.internal.types.AnyType;
import org.eclipse.wst.xml.xpath2.processor.internal.types.SchemaTypeValueFactory;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This class defines a set of methods to work with XPath 2.0 dynamic context variable "$value" that is needed for assertions evaluation.
 * 
 * @xerces.internal
 * 
 * @author Mukul Gandhi IBM
 * 
 * @version $Id$
 */
public class XSAssertionXPath2ValueImpl implements XSAssertionXPath2Value {

    /*
     * Determine "string value" of XPath2 context variable $value.
     */
    public String computeStringValueOf$value(Element currentAssertDomRootElem, ElementPSVI pElemPSVI) throws DOMException {
        
        NodeList childNodeList = currentAssertDomRootElem.getChildNodes();
        // there could be adjacent text nodes in the DOM tree. merge them to get the value.
        StringBuffer textValueContents = new StringBuffer();
        final int childListLength = childNodeList.getLength();
        int textChildCount = 0;
        // we are only interested in text & element nodes. store count of them in this variable.
        int effectiveChildNodeCount = 0;
        for (int childNodeIndex = 0; childNodeIndex < childListLength; childNodeIndex++) {
            Node node = childNodeList.item(childNodeIndex);
            short nodeType = node.getNodeType();
            if (nodeType == Node.TEXT_NODE) {
                textChildCount++;
                effectiveChildNodeCount++;
                textValueContents.append(node.getNodeValue());
            }
            else if (nodeType == Node.ELEMENT_NODE) {
                effectiveChildNodeCount++;  
            }
        }
        
        String strValueOf$value = "";        
        if (textChildCount == effectiveChildNodeCount) {
            // the DOM tree we are inspecting has simple content. therefore we can find the desired string value. 
            XSElementDeclaration elemDecl = pElemPSVI.getElementDeclaration();
            if ((elemDecl.getTypeDefinition()).derivedFrom(SchemaSymbols.URI_SCHEMAFORSCHEMA, SchemaSymbols.ATTVAL_STRING, XSConstants.DERIVATION_RESTRICTION)) {
                // if element's schema type is derived by restriction from xs:string, white-space normalization is not needed for the
                // string value for context variable $value.
                strValueOf$value = textValueContents.toString();  
            }
            else {
                // white-space normalization is needed for the string value of $value in case of derivation from non xs:string atomic types
                strValueOf$value = XMLChar.trim(textValueContents.toString());
            }    
        }
        else {
            // the DOM tree we are inspecting has 'mixed/element only' content.
            strValueOf$value = null; 
        }
        
        return strValueOf$value;
        
    } // computeStringValueOf$value
    

    /*
     * Given a string value, this method sets an XPath 2.0 typed value for variable "$value" in XPath dynamic context.
     */
    public void setXDMTypedValueFor$value(Element currentAssertDomRootElem, String value, XSSimpleTypeDefinition listOrUnionType, XSTypeDefinition attrType, boolean isTypeDerivedFromList, DynamicContext xpath2DynamicContext) throws Exception {
        
        // dummy schema short code initializer
        short xsdTypecode = -100;
        
        if (listOrUnionType != null) {
            if (isTypeDerivedFromList) {
                // $value is a sequence of atomic values (with type annotation xs:anyAtomicType*)
                // tokenize the list value by a sequence of white spaces
                StringTokenizer listStrTokens = new StringTokenizer(value, " \n\t\r");
                List xdmItemList = new ArrayList();
                while (listStrTokens.hasMoreTokens()) {
                    String itemValue = listStrTokens.nextToken();
                    xdmItemList.add(SchemaTypeValueFactory.newSchemaTypeValue(listOrUnionType.getBuiltInKind(), itemValue)); 
                }
                xpath2DynamicContext.set_variable(new org.eclipse.wst.xml.xpath2.processor.internal.types.QName("value"), XSTypeHelper.getXPath2ResultSequence(xdmItemList));                
            }
            else {
               xsdTypecode = getXercesXSDTypeCodeFor$value(listOrUnionType);
               setValueOf$valueForSTVarietyAtomic(value, xsdTypecode, xpath2DynamicContext);
            }
        }
        else {
           if (attrType != null) {
              // is value of an attribute
              xsdTypecode = getXercesXSDTypeCodeFor$value(attrType);
              setValueOf$valueForSTVarietyAtomic(value, xsdTypecode, xpath2DynamicContext);
           }
           else {
              // is "simple type" value of an element
              PSVIElementNSImpl currentAssertPSVINode = (PSVIElementNSImpl) currentAssertDomRootElem;
              XSTypeDefinition typeDef = currentAssertPSVINode.getTypeDefinition();
              if (typeDef instanceof XSComplexTypeDefinition && ((XSComplexTypeDefinition) typeDef).getSimpleType() != null) {
                  setValueOf$valueForCTWithSimpleContent(value, (XSComplexTypeDefinition) typeDef, xpath2DynamicContext);
              }
              else if (typeDef instanceof XSComplexTypeDefinition && ((XSComplexTypeDefinition) typeDef).getSimpleType() == null) {
                  // set xpath context variable $value to an empty sequence
                  xpath2DynamicContext.set_variable(new org.eclipse.wst.xml.xpath2.processor.internal.types.QName("value"), XSTypeHelper.getXPath2ResultSequence(new ArrayList())); 
              }
              else {
                  xsdTypecode = getXercesXSDTypeCodeFor$value(typeDef);
                  setValueOf$valueForSTVarietyAtomic(value, xsdTypecode, xpath2DynamicContext);
              }
           }
        }
        
    } // setXDMTypedValueFor$value
    
    
    /*
     * Given a string value, this method sets an XPath 2.0 typed value for variable "$value" in XPath dynamic context, when the value is for simpleType variety atomic. 
     */
    public void setValueOf$valueForSTVarietyAtomic(String value, short xsdTypecode, DynamicContext xpath2DynamicContext) {
        AnyType psychoPathType = SchemaTypeValueFactory.newSchemaTypeValue(xsdTypecode, value);
        xpath2DynamicContext.set_variable(new org.eclipse.wst.xml.xpath2.processor.internal.types.QName("value"), (AnyAtomicType) psychoPathType);
    } // setValueOf$valueForSTVarietyAtomic
    
    
    /*
     * Given a string value, this method sets an XPath 2.0 typed value for variable "$value" in XPath dynamic context, when the value is for simpleType variety list. 
     */
    public void setValueOf$valueForSTVarietyList(Element currentAssertDomRootElem, String listStrValue, XSSimpleTypeDefinition itemType, boolean isTypeDerivedFromList, DynamicContext xpath2DynamicContext) throws Exception {
        
        XSObjectList memberTypes = itemType.getMemberTypes();
        if (memberTypes.getLength() > 0) {
            // the list's item type has variety 'union'
            XSSimpleTypeDefinition actualListItemType = getActualListItemTypeForVarietyUnion(memberTypes, listStrValue);
            // set a schema 'typed value' to variable $value
            setXDMTypedValueFor$value(currentAssertDomRootElem, listStrValue, actualListItemType, null, false, xpath2DynamicContext);
        } 
        else {
            setXDMTypedValueFor$value(currentAssertDomRootElem, listStrValue, itemType, null, isTypeDerivedFromList, xpath2DynamicContext); 
        }

    } // setValueOf$valueForSTVarietyList
    
    /*
     * Given a string value, this method sets an XPath 2.0 typed value for variable "$value" in XPath dynamic context, if element has a complex type with simple content. 
     */
    private void setValueOf$valueForCTWithSimpleContent(String value, XSComplexTypeDefinition typeDef, DynamicContext xpath2DynamicContext) {
        
        XSComplexTypeDefinition cmplxTypeDef = (XSComplexTypeDefinition)typeDef;
        XSSimpleTypeDefinition complexTypeSimplContentType = cmplxTypeDef.getSimpleType();
        if (complexTypeSimplContentType.getVariety() == XSSimpleTypeDefinition.VARIETY_LIST) {
            // simple content type has variety xs:list
            XSSimpleTypeDefinition listItemType = complexTypeSimplContentType.getItemType();
            // tokenize the list value by a sequence of white spaces
            StringTokenizer values = new StringTokenizer(value, " \n\t\r");            
            // $value is a sequence of atomic values (with type annotation xs:anyAtomicType*)
            List xdmItemList = new ArrayList();
            final XSObjectList memberTypes = listItemType.getMemberTypes();
            if (memberTypes.getLength() > 0) {
               // itemType of xs:list has variety 'union'. here list items may have different types which are determined below.
               while (values.hasMoreTokens()) {
                   String itemValue = values.nextToken();
                   XSSimpleTypeDefinition listItemTypeForUnion = getActualListItemTypeForVarietyUnion(memberTypes, itemValue);
                   xdmItemList.add(SchemaTypeValueFactory.newSchemaTypeValue(listItemTypeForUnion.getBuiltInKind(), itemValue));
               }                                  
            }
            else {
               // every list item has a same type (the itemType of xs:list)
               while (values.hasMoreTokens()) {
                   String itemValue = values.nextToken();
                   xdmItemList.add(SchemaTypeValueFactory.newSchemaTypeValue(listItemType.getBuiltInKind(), itemValue)); 
               }                                  
            }

            // assign an XPath2 sequence to xpath context variable $value
            xpath2DynamicContext.set_variable(new org.eclipse.wst.xml.xpath2.processor.internal.types.QName("value"), XSTypeHelper.getXPath2ResultSequence(xdmItemList));
        }
        else if (complexTypeSimplContentType.getVariety() == XSSimpleTypeDefinition.VARIETY_UNION) {
            // simple content type has variety xs:union
            XSSimpleTypeDefinition simpleContentTypeForUnion = getActualListItemTypeForVarietyUnion(complexTypeSimplContentType.getMemberTypes(), value);
            xpath2DynamicContext.set_variable(new org.eclipse.wst.xml.xpath2.processor.internal.types.QName("value"), 
                                         SchemaTypeValueFactory.newSchemaTypeValue(simpleContentTypeForUnion.getBuiltInKind(), value));
        }
        else {
            // simple content type has variety atomic
            setValueOf$valueForSTVarietyAtomic(value, getXercesXSDTypeCodeFor$value(cmplxTypeDef.getSimpleType()), xpath2DynamicContext);
        }
          
    } // setValueOf$valueForCTWithSimpleContent
    
    
    /*
     * Given a string value, this method sets an XPath 2.0 typed value for variable "$value" in XPath dynamic context, when the value is for simpleType variety union. 
     */
    public void setValueOf$valueForSTVarietyUnion(String value, XSObjectList memberTypes, DynamicContext xpath2DynamicContext) {        
        // check member types of union in order to find that which member type can successfully validate the string value
        // first, and set value of XPath2 context variable $value using the member type found as its type annotation.
        for (int memTypeIdx = 0; memTypeIdx < memberTypes.getLength(); memTypeIdx++) {
            XSSimpleType simpleTypeDv = (XSSimpleType) memberTypes.item(memTypeIdx);
            if (XSTypeHelper.isValueValidForASimpleType(value, simpleTypeDv)) {
               setValueOf$valueForSTVarietyAtomic(value, getXercesXSDTypeCodeFor$value(simpleTypeDv), xpath2DynamicContext);
               break;
            }            
        }        
    } // setValueOf$valueForSTVarietyUnion
    
    
    /* 
     * Find the built-in Xerces schema 'type code' for XPath2 variable $value. This function recursively searches the XML schema type hierarchy navigating
     * up the base types, to find the needed built-in type.
     */
    private short getXercesXSDTypeCodeFor$value(XSTypeDefinition elementType) {

        if (Constants.NS_XMLSCHEMA.equals(elementType.getNamespace())) {
            short typeCode = -100; // dummy initializer

            boolean isxsd11Type = false;

            // the below 'if else' clauses are written to process few special cases handling few of schema types within PsychoPath XPath engine
            final String elementTypeName = elementType.getName();
            if ("dayTimeDuration".equals(elementTypeName)) {
                typeCode = PsychoPathTypeHelper.DAYTIMEDURATION_DT;
                isxsd11Type = true;
            }
            else if ("yearMonthDuration".equals(elementTypeName)) {
                typeCode = PsychoPathTypeHelper.YEARMONTHDURATION_DT;
                isxsd11Type = true;
            }

            return (isxsd11Type) ? typeCode : ((XSSimpleTypeDefinition) elementType).getBuiltInKind();    
        }
        else {
            return getXercesXSDTypeCodeFor$value(elementType.getBaseType()); 
        }

    } // getXercesXSDTypeCodeFor$value
    
    
    /*
     * Find the actual schema type of "list item" instance if the "item type" of list has variety union. 
     */
    private XSSimpleTypeDefinition getActualListItemTypeForVarietyUnion(XSObjectList memberTypes, String listItemStrValue) {

        XSSimpleTypeDefinition listItemType = null;
        
        // iterate the member types of union in order, to find that which schema type can successfully validate an atomic value first
        final int memberTypesLength = memberTypes.getLength();
        for (int memTypeIdx = 0; memTypeIdx < memberTypesLength; memTypeIdx++) {
           XSSimpleType memSimpleType = (XSSimpleType) memberTypes.item(memTypeIdx);
           if (XSTypeHelper.isValueValidForASimpleType(listItemStrValue, memSimpleType)) {
              // no more memberTypes need to be checked
              listItemType = memSimpleType; 
              break; 
           }
        }
        
        return listItemType;
        
    } // getActualListItemTypeForVarietyUnion

} // class XSAssertionXPath2ValueImpl
