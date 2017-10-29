/*
 * The Apache Software License, Version 1.1
 *
 *
 * Copyright (c) 1999 The Apache Software Foundation.  All rights 
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer. 
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:  
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Xerces" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written 
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation and was
 * originally based on software copyright (c) 1999, International
 * Business Machines, Inc., http://www.apache.org.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.apache.xerces.validators.dtd;

import org.apache.xerces.framework.*;
import org.apache.xerces.utils.StringPool;
import org.apache.xerces.utils.ImplementationMessages;

/**
 * DFAContentModel is the derivative of ContentModel that does
 * all of the non-trivial element content validation. This class does 
 * the conversion from the regular expression to the DFA that 
 * it then uses in its validation algorithm.
 * <p>
 * <b>Note:</b> Upstream work insures that this class will never see
 * a content model with PCDATA in it. Any model with PCDATA is 'mixed' 
 * and is handled via the MixedContentModel class since mixed models 
 * are very constrained in form and easily handled via a special case. 
 * This also makes implementation of this class much easier.
 *
 * @version
 */
public class DFAContentModel implements XMLContentModel
{

    //
    // Constants
    //

    // special strings

    /** Epsilon string. */
    private static final String fEpsilonString = "<<CMNODE_EPSILON>>";

    /** End-of-content string. */
    private static final String fEOCString = "<<CMNODE_EOC>>";

    // debugging

    /** Set to true to debug content model validation. */
    private static final boolean DEBUG_VALIDATE_CONTENT = false;

    // -----------------------------------------------------------------------
    //  Constructors
    // -----------------------------------------------------------------------

    /**
     * Constructs a DFA content model.
     *
     * @param elementIndex  The element decl index.
     * @param stringPool    The string pool.
     * @param declPool      The element decl pool.
     *
     * @exception CMException Thrown if DMA can't be built.
     */
    public DFAContentModel( StringPool      stringPool
                            , CMNode        syntaxTree
                            , int           leafCount) throws CMException
    {
        // Store away our index and pools in members
        fStringPool = stringPool;
        fLeafCount = leafCount;

        //
        //  Create some string pool indexes that represent the names of some
        //  magical nodes in the syntax tree.
        //
        /*** Defect 945 ***
        if (fEpsilonString == null)
        {
            fEpsilonString = new String("<<CMNODE_EPSILON>>");
            fEpsilonString.intern();
            fEOCString = new String("<<CMNODE_EOC>>");
            fEOCString.intern();
        }
        /***/
        fEpsilonIndex = fStringPool.addSymbol(fEpsilonString);
        fEOCIndex = fStringPool.addSymbol(fEOCString);

        //
        //  Ok, so lets grind through the building of the DFA. This method
        //  handles the high level logic of the algorithm, but it uses a
        //  number of helper classes to do its thing.
        //
        //  In order to avoid having hundreds of references to the error and
        //  string handlers around, this guy and all of his helper classes
        //  just throw a simple exception and we then pass it along.
        //
        buildDFA(syntaxTree);
    }


    // -----------------------------------------------------------------------
    //  Public, inherited methods
    // -----------------------------------------------------------------------
    
    /**
     * Check that the specified content is valid according to this
     * content model. This method can also be called to do 'what if' 
     * testing of content models just to see if they would be valid.
     * <p>
     * A value of -1 in the children array indicates a PCDATA node. All other 
     * indexes will be positive and represent child elements. The count can be
     * zero, since some elements have the EMPTY content model and that must be 
     * confirmed.
     *
     * @param childCount The number of entries in the <code>children</code> array.
     * @param children The children of this element.  Each integer is an index within
     *                 the <code>StringPool</code> of the child element name.  An index
     *                 of -1 is used to indicate an occurrence of non-whitespace character
     *                 data.
     *
     * @return The value -1 if fully valid, else the 0 based index of the child
     *         that first failed. If the value returned is equal to the number
     *         of children, then the specified children are valid but additional
     *         content is required to reach a valid ending state.
     *
     * @exception CMException Thrown on error.
     */
    public int validateContent(int childCount, int[] children) throws CMException
    {
        if (DEBUG_VALIDATE_CONTENT) 
            System.out.println("DFAContentModel#validateContent");

        //
        // A DFA content model must *always* have at least 1 child
        // so a failure is given if no children present.
        // 
        // Defect 782: This is an incorrect statement because a DFA
        // content model is also used for constructions such as:
        //
        //     (Optional*,NotRequired?)
        //
        // where a perfectly valid content would be NO CHILDREN.
        // Therefore, if there are no children, we must check to
        // see if the CMNODE_EOC marker is a valid start state! -Ac
        //
        if (childCount == 0) {
            if (DEBUG_VALIDATE_CONTENT) {
                System.out.println("!!! no children");
                System.out.println("elemMap="+fElemMap);
                for (int i = 0; i < fElemMap.length; i++) {
                    int elemIndex = fElemMap[i];
                    System.out.println("fElemMap["+i+"]="+elemIndex+
                                       " ("+
                                       fStringPool.toString(elemIndex)+
                                       ')');
                }
                System.out.println("EOCIndex="+fEOCIndex);
            }

            return fEmptyContentIsValid ? -1 : 0;

        } // if child count == 0

        //
        //  Lets loop through the children in the array and move our way
        //  through the states. Note that we use the fElemMap array to map
        //  an element index to a state index.
        //
        int curState = 0;
        for (int childIndex = 0; childIndex < childCount; childIndex++)
        {
            // Get the current element index out
            final int curElem = children[childIndex];

            // Look up this child in our element map
            int elemIndex = 0;
            for (; elemIndex < fElemMapSize; elemIndex++)
            {
                if (fElemMap[elemIndex] == curElem)
                    break;
            }

            // If we didn't find it, then obviously not valid
            if (elemIndex == fElemMapSize) {
                if (DEBUG_VALIDATE_CONTENT) 
                    System.out.println("!!! didn't find it");
                return childIndex;
                }

            //
            //  Look up the next state for this input symbol when in the
            //  current state.
            //
            curState = fTransTable[curState][elemIndex];

            // If its not a legal transition, then invalid
            if (curState == -1) {
                if (DEBUG_VALIDATE_CONTENT) 
                    System.out.println("!!! not a legal transition");
                return childIndex;
                }
        }

        //
        //  We transitioned all the way through the input list. However, that
        //  does not mean that we ended in a final state. So check whether
        //  our ending state is a final state.
        //
        if (DEBUG_VALIDATE_CONTENT) 
            System.out.println("curState="+curState+", childCount="+childCount);
        if (!fFinalStateFlags[curState])
            return childCount;

        // success!
        return -1;
    }

    /**
     * Returns information about which elements can be placed at a particular point
     * in the passed element's content model.
     * <p>
     * Note that the incoming content model to test must be valid at least up to
     * the insertion point. If not, then -1 will be returned and the info object
     * will not have been filled in.
     * <p>
     * If, on return, the info.isValidEOC flag is set, then the 'insert after'
     * element is a valid end of content. In other words, nothing needs to be
     * inserted after it to make the parent element's content model valid.
     *
     * @param fullyValid Only return elements that can be inserted and still
     *                   maintain the validity of subsequent elements past the
     *                   insertion point (if any).  If the insertion point is at
     *                   the end, and this is true, then only elements that can
     *                   be legal final states will be returned.
     * @param info An object that contains the required input data for the method,
     *             and which will contain the output information if successful.
     *
     * @return The value -1 if fully valid, else the 0 based index of the child
     *         that first failed before the insertion point. If the value 
     *         returned is equal to the number of children, then the specified
     *         children are valid but additional content is required to reach a
     *         valid ending state.
     *
     * @see InsertableElementsInfo
     */
    public int whatCanGoHere(boolean                    fullyValid
                            , InsertableElementsInfo    info) throws CMException
    {
        //
        //  First, lets make sure that the passed in current content is valid
        //  up to the insert point.
        //
        int curState = 0;
        for (int childIndex = 0; childIndex < info.insertAt; childIndex++)
        {
            // Get the current element index out
            final int curElem = info.curChildren[childIndex];

            // Look up this child in our element map
            int elemIndex = 0;
            for (; elemIndex < fElemMapSize; elemIndex++)
            {
                if (fElemMap[elemIndex] == curElem)
                    break;
            }

            // If we didn't find it, then not valid so return failure index
            if (elemIndex == fElemMapSize)
                return childIndex;

            //
            //  Look up the next state for this input symbol when in the
            //  current state.
            //
            curState = fTransTable[curState][elemIndex];

            // If its not a legal transition, then invalid
            if (curState == -1)
                return childIndex;
        }

        //
        //  If we got here, then curState is set to the state that would be
        //  the transition before the insertion point. We let this sit until
        //  below, where it will be needed.
        //
        final int insertState = curState;

        //
        //  Set any stuff we can know right off the bat for all cases. We know
        //  that this content model will never get PCData nodes because that
        //  is a mixed model. We can also set the valid EOC flag at this point
        //  since its just based on the state we ended in at the insert point.
        //
        info.canHoldPCData = false;
        info.isValidEOC = fFinalStateFlags[insertState];

        //
        //  Set the results count member and then see if we need to reallocate
        //  the outgoing arrays.
        //
        info.resultsCount = fElemMapSize;

        if ((info.results == null) || (info.results.length < info.resultsCount))
            info.results = new boolean[info.resultsCount];

        if ((info.possibleChildren == null)
        ||  (info.possibleChildren.length < info.resultsCount))
        {
            info.possibleChildren = new int[info.resultsCount];
        }

        //
        //  Fill in the possible children array, from our array. For each one
        //  of them, see if there is a valid transition from our insert at
        //  state on that input. Mark the results index for that child according
        //  to whether there is a transition or not.
        //
        for (int index = 0; index < fElemMapSize; index++)
        {
            info.possibleChildren[index] = fElemMap[index];
            info.results[index] = (fTransTable[insertState][index] != -1);
        }

        //
        //  If the fully valid parameter is set, then we have to go through
        //  the grunt work of plugging in each possible insertable element
        //  and running the DFA from that point to see if it would create a
        //  fully valid content model.
        //
        //  <TBD> When/if the validator is changed to be stateful, then change
        //  this stuff to start the exploratory validation at the insert state,
        //  not from the start each time.
        //
        if (fullyValid)
        {
            for (int index = 0; index < info.resultsCount; index++)
            {
                // Don't need to consider this one since its not insertable
                if (!info.results[index])
                    continue;

                // Stick this element into the insert at spot
                info.curChildren[info.insertAt] = info.possibleChildren[index];

                // And validate it. If it fails, then this one loses
                if (validateContent(info.childCount, info.curChildren) != -1)
                    info.results[index] = false;
            }
        }

        return -1;
    }


    // -----------------------------------------------------------------------
    //  Private methods
    // -----------------------------------------------------------------------
    private void buildDFA(CMNode syntaxTree) throws CMException
    {
        //
        //  The first step we need to take is to rewrite the content model
        //  using our CMNode objects, and in the process get rid of any
        //  repetition short cuts, converting them into '*' style repetitions
        //  or getting rid of repetitions altogether.
        //
        //  The conversions done are:
        //
        //  x+ -> (x|x*)
        //  x? -> (x|epsilon)
        //
        //  This is a relatively complex scenario. What is happening is that
        //  we create a top level binary node of which the special EOC value
        //  is set as the right side node. The the left side is set to the
        //  rewritten syntax tree. The source is the original content model
        //  info from the decl pool. The rewrite is done by buildSyntaxTree()
        //  which recurses the decl pool's content of the element and builds
        //  a new tree in the process.
        //
        //  Note that, during this operation, we set each non-epsilon leaf
        //  node's DFA state position and count the number of such leafs, which
        //  is left in the fLeafCount member.
        //
        //  The nodeTmp object is passed in just as a temp node to use during
        //  the recursion. Otherwise, we'd have to create a new node on every
        //  level of recursion, which would be piggy in Java (as is everything
        //  for that matter.)
        //
        CMLeaf nodeEOC = new CMLeaf(XMLContentSpecNode.CONTENTSPECNODE_LEAF, fEOCIndex);
        fHeadNode = new CMBinOp
        (
            XMLContentSpecNode.CONTENTSPECNODE_SEQ
            , syntaxTree
            , nodeEOC
        );

        //
        //  And handle specially the EOC node, which also must be numbered
        //  and counted as a non-epsilon leaf node. It could not be handled
        //  in the above tree build because it was created before all that
        //  started. We save the EOC position since its used during the DFA
        //  building loop.
        //
        fEOCPos = fLeafCount;
        nodeEOC.setPosition(fLeafCount++);

        //
        //  Ok, so now we have to iterate the new tree and do a little more
        //  work now that we know the leaf count. One thing we need to do is
        //  to calculate the first and last position sets of each node. This
        //  is cached away in each of the nodes.
        //
        //  Along the way we also set the leaf count in each node as the
        //  maximum state count. They must know this in order to create their
        //  first/last pos sets.
        //
        //  We also need to build an array of references to the non-epsilon
        //  leaf nodes. Since we iterate it in the same way as before, this
        //  will put them in the array according to their position values.
        //
        fLeafList = new CMLeaf[fLeafCount];
        postTreeBuildInit(fHeadNode, 0);

        //
        //  And, moving onward... We now need to build the follow position
        //  sets for all the nodes. So we allocate an array of state sets,
        //  one for each leaf node (i.e. each DFA position.)
        //
        fFollowList = new CMStateSet[fLeafCount];
        for (int index = 0; index < fLeafCount; index++)
            fFollowList[index] = new CMStateSet(fLeafCount);
        calcFollowList(fHeadNode);
        //
        //  And finally the big push... Now we build the DFA using all the
        //  states and the tree we've built up. First we set up the various
        //  data structures we are going to use while we do this.
        //
        //  First of all we need an array of unique element names in our
        //  content model. For each transition table entry, we need a set of
        //  contiguous indices to represent the transitions for a particular
        //  input element. So we need to a zero based range of indexes that
        //  map to element types. This element map provides that mapping.
        //
        fElemMap = new int[fLeafCount];
        fElemMapSize = 0;
        for (int outIndex = 0; outIndex < fLeafCount; outIndex++)
        {
            // Get the current leaf's element index
            final int elemIndex = fLeafList[outIndex].getElemIndex();

            // See if the current leaf node's element index is in the list
            int inIndex = 0;
            for (; inIndex < fElemMapSize; inIndex++)
            {
                if (fElemMap[inIndex] == elemIndex)
                    break;
            }

            // If it was not in the list, then add it, if not the EOC node
            if (inIndex == fElemMapSize)
                fElemMap[fElemMapSize++] = elemIndex;
        }

        //
        //  Next lets create some arrays, some that that hold transient
        //  information during the DFA build and some that are permament.
        //  These are kind of sticky since we cannot know how big they will
        //  get, but we don't want to use any Java collections because of
        //  performance.
        //
        //  Basically they will probably be about fLeafCount*2 on average,
        //  but can be as large as 2^(fLeafCount*2), worst case. So we start
        //  with fLeafCount*4 as a middle ground. This will be very unlikely
        //  to ever have to expand, though it if does, the overhead will be
        //  somewhat ugly.
        //
        int curArraySize = fLeafCount * 4;
        CMStateSet[] statesToDo = new CMStateSet[curArraySize];
        fFinalStateFlags = new boolean[curArraySize];
        fTransTable = new int[curArraySize][];

        //
        //  Ok we start with the initial set as the first pos set of the
        //  head node (which is the seq node that holds the content model
        //  and the EOC node.)
        //
        CMStateSet setT = fHeadNode.firstPos();

        //
        //  Init our two state flags. Basically the unmarked state counter
        //  is always chasing the current state counter. When it catches up,
        //  that means we made a pass through that did not add any new states
        //  to the lists, at which time we are done. We could have used a
        //  expanding array of flags which we used to mark off states as we
        //  complete them, but this is easier though less readable maybe.
        //
        int unmarkedState = 0;
        int curState = 0;

        //
        //  Init the first transition table entry, and put the initial state
        //  into the states to do list, then bump the current state.
        //
        fTransTable[curState] = makeDefStateList();
        statesToDo[curState] = setT;
        curState++;

        //
        //  Ok, almost done with the algorithm... We now enter the
        //  loop where we go until the states done counter catches up with
        //  the states to do counter.
        //
        while (unmarkedState < curState)
        {
            //
            //  Get the first unmarked state out of the list of states to do.
            //  And get the associated transition table entry.
            //
            setT = statesToDo[unmarkedState];
            int[] transEntry = fTransTable[unmarkedState];

            // Mark this one final if it contains the EOC state
            fFinalStateFlags[unmarkedState] = setT.getBit(fEOCPos);

            // Bump up the unmarked state count, marking this state done
            unmarkedState++;

            // Loop through each possible input symbol in the element map
            CMStateSet newSet = null;
            for (int elemIndex = 0; elemIndex < fElemMapSize; elemIndex++)
            {
                //
                //  Build up a set of states which is the union of all of
                //  the follow sets of DFA positions that are in the current
                //  state. If we gave away the new set last time through then
                //  create a new one. Otherwise, zero out the existing one.
                //
                if (newSet == null)
                    newSet = new CMStateSet(fLeafCount);
                else
                    newSet.zeroBits();

                for (int leafIndex = 0; leafIndex < fLeafCount; leafIndex++)
                {
                    // If this leaf index (DFA position) is in the current set...
                    if (setT.getBit(leafIndex))
                    {
                        //
                        //  If this leaf is the current input symbol, then we
                        //  want to add its follow list to the set of states to
                        //  transition to from the current state.
                        //
                        if (fLeafList[leafIndex].getElemIndex() == fElemMap[elemIndex])
                            newSet.union(fFollowList[leafIndex]);
                    }
                }

                //
                //  If this new set is not empty, then see if its in the list
                //  of states to do. If not, then add it.
                //
                if (!newSet.isEmpty())
                {
                    //
                    //  Search the 'states to do' list to see if this new
                    //  state set is already in there.
                    //
                    int stateIndex = 0;
                    for (; stateIndex < curState; stateIndex++)
                    {
                        if (statesToDo[stateIndex].isSameSet(newSet))
                            break;
                    }

                    // If we did not find it, then add it
                    if (stateIndex == curState)
                    {
                        //
                        //  Put this new state into the states to do and init
                        //  a new entry at the same index in the transition
                        //  table.
                        //
                        statesToDo[curState] = newSet;
                        fTransTable[curState] = makeDefStateList();

                        // We now have a new state to do so bump the count
                        curState++;

                        //
                        //  Null out the new set to indicate we adopted it.
                        //  This will cause the creation of a new set on the
                        //  next time around the loop.
                        //
                        newSet = null;
                    }

                    //
                    //  Now set this state in the transition table's entry
                    //  for this element (using its index), with the DFA
                    //  state we will move to from the current state when we
                    //  see this input element.
                    //
                    transEntry[elemIndex] = stateIndex;

                    // Expand the arrays if we're full
                    if (curState == curArraySize)
                    {
                        //
                        //  Yikes, we overflowed the initial array size, so
                        //  we've got to expand all of these arrays. So adjust
                        //  up the size by 50% and allocate new arrays.
                        //
                        final int newSize = (int)(curArraySize * 1.5);
                        CMStateSet[] newToDo = new CMStateSet[newSize];
                        boolean[] newFinalFlags = new boolean[newSize];
                        int[][] newTransTable = new int[newSize][];

                        // Copy over all of the existing content
                        for (int expIndex = 0; expIndex < curArraySize; expIndex++)
                        {
                            newToDo[expIndex] = statesToDo[expIndex];
                            newFinalFlags[expIndex] = fFinalStateFlags[expIndex];
                            newTransTable[expIndex] = fTransTable[expIndex];
                        }

                        // Store the new array size
                        curArraySize = newSize;
                        statesToDo = newToDo;
                        fFinalStateFlags = newFinalFlags;
                        fTransTable = newTransTable;
                    }
                }
            }
        }

        // Check to see if we can set the fEmptyContentIsValid flag.
        fEmptyContentIsValid = ((CMBinOp)fHeadNode).getLeft().isNullable();

        //
        //  And now we can say bye bye to the temp representation since we've
        //  built the DFA.
        //
        if (DEBUG_VALIDATE_CONTENT) 
            dumpTree(fHeadNode, 0);
        fHeadNode = null;
        fLeafList = null;
        fFollowList = null;

    }


    private void calcFollowList(CMNode nodeCur) throws CMException
    {
        // Recurse as required
        if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_CHOICE)
        {
            // Recurse only
            calcFollowList(((CMBinOp)nodeCur).getLeft());
            calcFollowList(((CMBinOp)nodeCur).getRight());
        }
         else if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_SEQ)
        {
            // Recurse first
            calcFollowList(((CMBinOp)nodeCur).getLeft());
            calcFollowList(((CMBinOp)nodeCur).getRight());

            //
            //  Now handle our level. We use our left child's last pos
            //  set and our right child's first pos set, so go ahead and
            //  get them ahead of time.
            //
            final CMStateSet last  = ((CMBinOp)nodeCur).getLeft().lastPos();
            final CMStateSet first = ((CMBinOp)nodeCur).getRight().firstPos();

            //
            //  Now, for every position which is in our left child's last set
            //  add all of the states in our right child's first set to the
            //  follow set for that position.
            //
            for (int index = 0; index < fLeafCount; index++)
            {
                if (last.getBit(index))
                    fFollowList[index].union(first);
            }
        }
         else if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_ZERO_OR_MORE)
        {
            // Recurse first
            calcFollowList(((CMUniOp)nodeCur).getChild());

            //
            //  Now handle our level. We use our own first and last position
            //  sets, so get them up front.
            //
            final CMStateSet first = nodeCur.firstPos();
            final CMStateSet last  = nodeCur.lastPos();

            //
            //  For every position which is in our last position set, add all
            //  of our first position states to the follow set for that
            //  position.
            //
            for (int index = 0; index < fLeafCount; index++)
            {
                if (last.getBit(index))
                    fFollowList[index].union(first);
            }
        }
         else if ((nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_ONE_OR_MORE)
              ||  (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_ZERO_OR_ONE))
        {
            throw new CMException(ImplementationMessages.VAL_NIICM);
        }
    }


    private void dumpTree(CMNode nodeCur, int level) throws CMException
    {
        for (int index = 0; index < level; index++)
            System.out.print("   ");

        int type = nodeCur.type();
        if ((type == XMLContentSpecNode.CONTENTSPECNODE_CHOICE)
        ||  (type == XMLContentSpecNode.CONTENTSPECNODE_SEQ))
        {
            if (type == XMLContentSpecNode.CONTENTSPECNODE_CHOICE)
                System.out.print("Choice Node ");
            else
                System.out.print("Seq Node ");

            if (nodeCur.isNullable())
                System.out.print("Nullable ");

            System.out.print("firstPos=");
            System.out.print(nodeCur.firstPos().toString());
            System.out.print(" lastPos=");
            System.out.println(nodeCur.lastPos().toString());

            dumpTree(((CMBinOp)nodeCur).getLeft(), level+1);
            dumpTree(((CMBinOp)nodeCur).getRight(), level+1);
        }
         else if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_ZERO_OR_MORE)
        {
            System.out.print("Rep Node ");

            if (nodeCur.isNullable())
                System.out.print("Nullable ");

            System.out.print("firstPos=");
            System.out.print(nodeCur.firstPos().toString());
            System.out.print(" lastPos=");
            System.out.println(nodeCur.lastPos().toString());

            dumpTree(((CMUniOp)nodeCur).getChild(), level+1);
        }
         else if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_LEAF)
        {
            System.out.print
            (
                "Leaf: (pos="
                + ((CMLeaf)nodeCur).getPosition()
                + "), "
                + fStringPool.toString(((CMLeaf)nodeCur).getElemIndex())
                + "(elemIndex="
                + ((CMLeaf)nodeCur).getElemIndex()
                + ") "
            );

            if (nodeCur.isNullable())
                System.out.print(" Nullable ");

            System.out.print("firstPos=");
            System.out.print(nodeCur.firstPos().toString());
            System.out.print(" lastPos=");
            System.out.println(nodeCur.lastPos().toString());
        }
         else
        {
            throw new CMException(ImplementationMessages.VAL_NIICM);
        }
    }


    //
    //  -1 is used to represent bad transitions in the transition table
    //  entry for each state. So each entry is initialized to an all -1
    //  array. This method creates a new entry and initializes it.
    //
    private int[] makeDefStateList()
    {
        int[] retArray = new int[fElemMapSize];
        for (int index = 0; index < fElemMapSize; index++)
            retArray[index] = -1;
        return retArray;
    }


    private int postTreeBuildInit(CMNode nodeCur, int curIndex) throws CMException
    {
        // Set the maximum states on this node
        nodeCur.setMaxStates(fLeafCount);

        // Recurse as required
        if ((nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_CHOICE)
        ||  (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_SEQ))
        {
            curIndex = postTreeBuildInit(((CMBinOp)nodeCur).getLeft(), curIndex);
            curIndex = postTreeBuildInit(((CMBinOp)nodeCur).getRight(), curIndex);
        }
         else if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_ZERO_OR_MORE)
        {
            curIndex = postTreeBuildInit(((CMUniOp)nodeCur).getChild(), curIndex);
        }
         else if (nodeCur.type() == XMLContentSpecNode.CONTENTSPECNODE_LEAF)
        {
            //
            //  Put this node in the leaf list at the current index if its
            //  a non-epsilon leaf.
            //
            if (((CMLeaf)nodeCur).getElemIndex() != fEpsilonIndex)
                fLeafList[curIndex++] = (CMLeaf)nodeCur;
        }
         else
        {
            throw new CMException(ImplementationMessages.VAL_NIICM);
        }
        return curIndex;
    }


    // -----------------------------------------------------------------------
    //  Private data members
    //
    //  fDeclPool
    //      The element/attribute declaration pool of our parser session.
    //      This is set during ctor and kept around.
    //
    //  fElementIndex
    //      The Element Decl pool index of the element that we are the content
    //      model for.
    //
    //  fElemMap
    //  fElemMapSize
    //      This is the map of unique input symbol elements to indices into
    //      each state's per-input symbol transition table entry. This is part
    //      of the built DFA information that must be kept around to do the
    //      actual validation.
    //
    //  fEOCIndex
    //      The string index for the 'end of content' string that we add to
    //      the string pool. This is used as the special name of an element
    //      that represents the end of the syntax tree.
    //
    //  fEOCPos
    //      The NFA position of the special EOC (end of content) node. This
    //      is saved away since its used during the DFA build.
    //
    //  fEpsilonIndex
    //      The string index for the 'epsilon' string that we add to the
    //      string pool. This represents epsilon node transitions in the
    //      syntax tree.
    //
    //  fErrHandler
    //      The object to report errors to.
    //
    //  fFinalStateFlags
    //      This is an array of booleans, one per state (there are
    //      fTransTableSize states in the DFA) that indicates whether that
    //      state is a final state.
    //
    //  fFollowList
    //      The list of follow positions for each NFA position (i.e. for each
    //      non-epsilon leaf node.) This is only used during the building of
    //      the DFA, and is let go afterwards.
    //
    //  fHeadNode
    //      This is the head node of our intermediate representation. It is
    //      only non-null during the building of the DFA (just so that it
    //      does not have to be passed all around.) Once the DFA is built,
    //      this is no longer required so its nulled out.
    //
    //  fLeafCount
    //      The count of leaf nodes. This is an important number that set some
    //      limits on the sizes of data structures in the DFA process.
    //
    //  fLeafList
    //      An array of non-epsilon leaf nodes, which is used during the DFA
    //      build operation, then dropped.
    //
    //  fSpecNode
    //      The content spec node for the element that this object represents
    //      the content of. This info is needed a good bit so we get it once
    //      and keep it.
    //
    //  fStringPool
    //      The string pool of our parser session. This is set during ctor
    //      and kept around.
    //
    //  fTransTable
    //  fTransTableSize
    //      This is the transition table that is the main by product of all
    //      of the effort here. It is an array of arrays of ints. The first
    //      dimension is the number of states we end up with in the DFA. The
    //      second dimensions is the number of unique elements in the content
    //      model (fElemMapSize). Each entry in the second dimension indicates
    //      the new state given that input for the first dimension's start
    //      state.
    //
    //      The fElemMap array handles mapping from element indexes to
    //      positions in the second dimension of the transition table.
    //
    //      fTransTableSize is the number of valid entries in the transition
    //      table, and in the other related tables such as fFinalStateFlags.
    // -----------------------------------------------------------------------
    private int[]           fElemMap        = null;
    private int             fElemMapSize    = 0;
    private int             fEOCIndex       = 0;
    private int             fEOCPos         = 0;
    private int             fEpsilonIndex   = 0;
    private boolean[]       fFinalStateFlags = null;
    private CMStateSet[]    fFollowList     = null;
    private CMNode          fHeadNode       = null;
    private int             fLeafCount      = 0;
    private CMLeaf[]        fLeafList       = null;
    private StringPool      fStringPool     = null;
    private int[][]         fTransTable     = null;
    private int             fTransTableSize = 0;

    /**
     * Flag that indicates that even though we have a "complicated"
     * content model, it is valid to have no content. In other words,
     * all parts of the content model are optional. For example:
     * <pre>
     *      &lt;!ELEMENT AllOptional (Optional*,NotRequired?)&gt;
     * </pre>
     */
    private boolean fEmptyContentIsValid = false;

}