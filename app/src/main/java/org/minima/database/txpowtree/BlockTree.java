package org.minima.database.txpowtree;

import java.math.BigInteger;
import java.util.ArrayList;

import org.minima.database.MinimaDB;
import org.minima.database.mmr.MMRSet;
import org.minima.database.txpowdb.TxPOWDBRow;
import org.minima.objects.TxPoW;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.MinimaLogger;

public class BlockTree {
	/**
	 * ROOT node of the Chain
	 */
	BlockTreeNode mRoot;
	
	/**
	 * The Tip of the longest Chain
	 */
	public BlockTreeNode mTip;
	
	/**
	 * The Block beyond which you are cascading and parents may be of a higher level
	 */
	BlockTreeNode mCascadeNode;
	
	/**
	 * When searching for the tip.. 
	 */
	BlockTreeNode _mOldTip;
	
	/**
	 * When Copying..
	 */
	BlockTreeNode mCopyNode;
	
	/**
	 * Main Constructor
	 */
	public BlockTree() {}
	
	public void setTreeRoot(BlockTreeNode zNode) {
		zNode.setParent(null);
		mRoot 			= zNode;
		mTip 			= mRoot;
		mCascadeNode 	= mRoot;
	}
	
	public BlockTreeNode getChainRoot() {
		return mRoot;
	}
	
	public BlockTreeNode getChainTip() {
		return mTip;
	}
	
	public BlockTreeNode getCascadeNode() {
		return mCascadeNode;
	}
	
	/**
	 * The parent MUST exist before calling this!
	 * 
	 * @param zNode
	 * @return
	 */
	public boolean addNode(BlockTreeNode zNode) {
		//Do we have it allready.. DOUBLE check as this done already
		BlockTreeNode exists = findNode(zNode.getTxPowID());
		if(exists != null) {
			//All ready in there..
			return false;
		}
		
		//Otherwise get the parent block and add this to that
		MiniData prevblock = zNode.getTxPow().getParentID();
		
		//Find the parent block.. from last uncascaded node onwards
		BlockTreeNode parent = findNode(prevblock);
		
		//Do we have a parent..
		if(parent == null) {
//			MinimaLogger.log("NO PARENT FOR BLOCK : "+zNode.getTxPow().getBlockNumber());
			//No direct parent..  add to the pool and ask for parent
			return false;
		}
		
		//Check after the cascade node..
		if(zNode.getTxPow().getBlockNumber().isLessEqual(getCascadeNode().getTxPow().getBlockNumber())) {
			MinimaLogger.log("BlockTree : BLOCK PAST CASCADE NODE.. "+zNode.getTxPow());
			return false;
		}
		
		//It's OK - add it
		parent.addChild(zNode);

		//It's been added
		return true;
	}
	
	/**
	 * Adds the node regardless of the parents.. used when cascading
	 * @param zNode
	 * @return
	 */
	public void hardAddNode(BlockTreeNode zNode, boolean zTouchMMR) {
		if(mRoot == null) {
			setTreeRoot(zNode);
			zNode.setParent(null);
			return;
		}
		
		//Add to the end..
		mTip.addChild(zNode);
		
		//Link the MMRSet
		if(zTouchMMR) {
			if(zNode.getMMRSet() != null) {
				if(mTip.getTxPowID().isEqual(zNode.getTxPow().getParentID())) {
					//Correct Parent.. can link the MMR!
					zNode.getMMRSet().setParent(mTip.getMMRSet());	
				}else {
					zNode.getMMRSet().setParent(null);
				}
			}
		}
				
		//Move on..
		mTip = zNode;
	}
	
	public void hardSetCascadeNode(BlockTreeNode zNode) {
		mCascadeNode = zNode;
	}
	
	/**
	 * Resets the weights in the tree
	 */
	public void resetWeights() {
		//Store the Old Tip
		_mOldTip = mTip;
		
		//First default them
		_zeroWeights();
		
		//Start at root..
		_cascadeWeights();
		
		//And get the tip..
		mTip = _getHeaviestBranchTip();		
	}
	
	/**
	 * Set all the weights to 0
	 */
	private void _zeroWeights() {
		//Go down the whole tree..
		_recurseTree(new NodeAction() {
			@Override
			public void runAction(BlockTreeNode zNode) {
				zNode.resetCurrentWeight();
			}
		});
	}
	
	/**
	 * Calculate the correct weights per block on the chain
	 */
	private void _cascadeWeights() {
		//First lets stream up the OLD main chain.. OPTIMISATION
		if(_mOldTip != null) {
			BigInteger weight        = _mOldTip.getWeight();
			_mOldTip.mCascadeWeighted = true;
			
			//Add to all the parents..
			BlockTreeNode parent = _mOldTip.getParent();
			while(parent != null) {
				//A new cascading weight
				BigInteger newweight = weight.add(parent.getWeight()); 
				
				//Add to this parent..
				parent.mCascadeWeighted = true;
				parent.addToTotalWeight(weight);
				parent = parent.getParent();
				
				//Set the new weight
				weight = newweight;
			}
		}
		
		//Add all the weights up..
		_recurseTree(new NodeAction() {
			@Override
			public void runAction(BlockTreeNode zNode) {
				//Only add valid blocks
				if(zNode.getState() == BlockTreeNode.BLOCKSTATE_VALID && !zNode.mCascadeWeighted) {
					//The weight of this block
					BigInteger weight = zNode.getWeight();
					
					//Add to all the parents..
					BlockTreeNode parent = zNode.getParent();
					while(parent != null) {
						parent.addToTotalWeight(weight);
						parent = parent.getParent();
					}
				}
			}
		});
	}
	
	/**
	 * Pick the GHOST heaviest Branch of the tree
	 * @param zStartNode
	 * @return
	 */
	private BlockTreeNode _getHeaviestBranchTip() {
		//If nothing on chain return nothing
		if(getChainRoot() == null) {
			return null;
		}
	
		//Start at root
		BlockTreeNode curr = getChainRoot();
		
		while(true) {
			//Get the heaviest child branch
			ArrayList<BlockTreeNode> children = curr.getChildren();
			
			//Do we have any children
			if(children.size()==0) {
				return curr;
			}
			
			//Only keep the heaviest
			BlockTreeNode heavy = null;
			for(BlockTreeNode node : children) {
				if(node.getState() == BlockTreeNode.BLOCKSTATE_VALID) {
					if(heavy == null) {
						heavy = node;
					}else {
						if(node.getTotalWeight().compareTo(heavy.getTotalWeight()) > 0) {
							heavy = node;
						}
					}
				}
			}
			
			//reset and do it again!
			curr = heavy;
		}
	}
	
	/**
	 * Find a specific node in the tree
	 * 
	 * @param zTxPOWID
	 * @return
	 */
	
	public BlockTreeNode findNode(MiniData zTxPOWID) {
		//Action that checks for a specific node..
		NodeAction finder = new NodeAction(zTxPOWID) {
			@Override
			public void runAction(BlockTreeNode zNode) {
				if(zNode.getTxPowID().isEqual(getExtraData())) {
        			setReturnObject(zNode);
        		}
			}
		}; 
		
		return _recurseTree(finder);
	}
	
	/**
	 * Sort the Block tree nodes.. ONLY Full blocks with valid parents get checked
	 * @param zMainDB
	 */
	public void sortBlockTreeNodeStates(MinimaDB zMainDB) {
		//Action that checks for a specific node..
		NodeAction nodestates = new NodeAction(zMainDB) {
			@Override
			public void runAction(BlockTreeNode zNode) {
				//What ID
				MiniData txpowid = zNode.getTxPowID();
				
				//Get the txpow row
				TxPOWDBRow row = getDB().getTxPOWRow(txpowid);
				
				//Check fpr chain root..
				int parentstate = BlockTreeNode.BLOCKSTATE_INVALID;
				if(getChainRoot().getTxPowID().isEqual(txpowid)) {
					parentstate = BlockTreeNode.BLOCKSTATE_VALID;
				}else {
					parentstate = zNode.getParent().getState();
				}
				
				//Must be a valid parent for anything to happen
				if(parentstate == BlockTreeNode.BLOCKSTATE_INVALID) {
					//All Children are INVALID
					zNode.setState(BlockTreeNode.BLOCKSTATE_INVALID);
				
				}else if(parentstate == BlockTreeNode.BLOCKSTATE_VALID) {
					//Do we check.. only when full
					if(zNode.getState() == BlockTreeNode.BLOCKSTATE_BASIC && row.getBlockState() == TxPOWDBRow.TXPOWDBROW_STATE_FULL) {
						//Need allok for the block to be accepted
						boolean allok = false;
						
						//Check that Block difficulty is Correct!?
						//..TODO
						
						//Check the Super Block Levels are Correct! and point to the correct blocks
						//..TODO
						
						//need a  body for this..
						if(row.getTxPOW().hasBody()) {
							//Create an MMR set that will ONLY be used if the block is VALID..
							MMRSet mmrset = new MMRSet(zNode.getParent().getMMRSet());
							
							//Set this MMR..
							zNode.setMMRset(mmrset);
							
							//Check all the transactions in the block are correct..
							allok = getDB().checkFullTxPOW(zNode.getTxPow(), mmrset);
							
							//Check the root MMR..
							if(allok) {
								MiniData root = mmrset.getMMRRoot().getFinalHash();
								if(!row.getTxPOW().getMMRRoot().isEqual(root)) {
									allok = false;	
								}
							}
						}else {
							MinimaLogger.log("WARNING : sortBlockTreeNodeStates on no body TxPoW..! "+zNode.toString());
						}
						
						//if it all passes is OK.. otherwise not ok..
						if(allok) {
							//it's all valid!
							zNode.setState(BlockTreeNode.BLOCKSTATE_VALID);
						}else{
							//No good..
							zNode.setState(BlockTreeNode.BLOCKSTATE_INVALID);
						}
					}
				}
			}
		}; 
		
		_recurseTree(nodestates);
	}
		
	/**
	 * Deep Copy a Node and it's children
	 * 
	 * @param zStartNode
	 * @return
	 */
	public static BlockTreeNode copyTreeNode(BlockTreeNode zStartNode) {
		//Create a STACK..
		NodeStack stack     = new NodeStack();
		NodeStack stackcopy = new NodeStack();
		
		//Now loop..
		BlockTreeNode curr     = zStartNode; 
		curr.mTraversedChild   = 0;
		
		//The Copy..
		BlockTreeNode rootcopy   = null;
		BlockTreeNode currparent = null; 
		BlockTreeNode currcopy   = null; 
		
        // traverse the tree 
        while (curr != null || !stack.isEmpty()) { 
        	while (curr !=  null) { 
            	//Copy the Node..
        		currcopy = new BlockTreeNode(curr);
        		if(rootcopy == null) {
        			rootcopy = currcopy;
        		}
        		if(currparent != null) {
        			currparent.addChild(currcopy);
        		}
        		
            	//Push on the stack..
            	stack.push(curr); 
            	stackcopy.push(currcopy);
            	
            	//Does it have children
            	int childnum = curr.mTraversedChild;
            	if(curr.getNumberChildren() > childnum) {
                	curr.mTraversedChild++;
                	
                	//Keep as the parent..
                	currparent = currcopy;
                			
                	curr = curr.getChild(childnum); 
                	curr.mTraversedChild = 0;
                }else {
                	curr = null;
                }
            } 
  
            //Current must be NULL at this point
            curr     = stack.peek();
            currcopy = stackcopy.peek();
            
            //Get the next child..
            int childnum = curr.mTraversedChild;
        	if(curr.getNumberChildren() > childnum) {
            	//Increment so next time a different child is chosen
        		curr.mTraversedChild++;
            	
        		//Keep as the parent..
            	currparent = currcopy;
            	
        		curr = curr.getChild(childnum); 
            	curr.mTraversedChild = 0;
            }else{
            	//We've seen all the children.. remove from the stack
            	stack.pop();
            	stackcopy.pop();
            	
            	//Reset the current to null
            	curr = null;
            }
        }
        
        return rootcopy;
	}
	
	/**
	 * Simple Printer
	 */
	public void printTree() {
		//Action that checks for a specific node..
		NodeAction printer = new NodeAction() {
			@Override
			public void runAction(BlockTreeNode zNode) {
				BlockTreeNode parent = zNode.getParent();
				if(parent!=null) {
					MinimaLogger.log(zNode.getTxPowID().to0xString(10)+" parent:"+zNode.getParent().getTxPowID());	
				}else {
					MinimaLogger.log(zNode.getTxPowID().to0xString(10)+" ROOT");
				}
			}
		}; 
		
		_recurseTree(printer);
	}
	
	/**
	 * Recurse the whole tree and ru an action..
	 * 
	 * return object if something special found..
	 * 
	 * @param zNodeAction
	 * @return
	 */
	private BlockTreeNode _recurseTree(NodeAction zNodeAction) {
		//If nothing on chain return nothing
		if(getChainRoot() == null) {return null;}
				
		return _recurseTree(zNodeAction, getChainRoot());
	}
	
	private BlockTreeNode _recurseTree(NodeAction zNodeAction, BlockTreeNode zStartNode) {
		//Create a STACK..
		NodeStack stack = new NodeStack();
		
		//Now loop..
		BlockTreeNode curr   = zStartNode; 
		curr.mTraversedChild = 0;
		
        // traverse the tree 
        while (curr != null || !stack.isEmpty()) { 
        	while (curr !=  null) { 
            	//Run the Action
        		zNodeAction.runAction(curr);
        		
        		//Have we found what we were looking for..
        		if(zNodeAction.returnObject()) {
        			return zNodeAction.getObject();
        		}
            	
            	//Push on the stack..
            	stack.push(curr); 
            	
            	//Does it have children
            	int childnum = curr.mTraversedChild;
            	if(curr.getNumberChildren() > childnum) {
                	curr.mTraversedChild++;
                	curr = curr.getChild(childnum); 
                	curr.mTraversedChild = 0;
                }else {
                	curr = null;
                }
            } 
  
            //Current must be NULL at this point
            curr = stack.peek();
        	
            //Get the next child..
            int childnum = curr.mTraversedChild;
        	if(curr.getNumberChildren() > childnum) {
            	//Increment so next time a different child is chosen
        		curr.mTraversedChild++;
            	curr = curr.getChild(childnum); 
            	curr.mTraversedChild = 0;
            }else{
            	//We've seen all the children.. remove from the stack
            	stack.pop();
            	
            	//Reset the current to null
            	curr = null;
            }
        }
        
        return null;
	}
	
	/**
	 * Get the list of TreeNodes in the LongestChain
	 */
	public ArrayList<BlockTreeNode> getAsList(){
		return getAsList(false);
	}
	
	public ArrayList<BlockTreeNode> getAsList(boolean zReverse){
		ArrayList<BlockTreeNode> nodes  = new ArrayList<>();
	
		//Do we have a tip.. ?
		if(mTip == null) {
			return nodes;
		}
		
		//Add to the list..
		nodes.add(mTip);
		
		//Cycle up through the parents
		BlockTreeNode tip = mTip.getParent();
		while(tip != null) {
			if(zReverse) {
				nodes.add(0,tip);
			}else {
				nodes.add(tip);	
			}
			tip = tip.getParent();
		}
				
		return nodes;
	}
	
	/** 
	 * Clear the TxPoW Body and MMRset from all nodes past the cascade
	 */
	public void clearCascadeBody() {
		if(mCascadeNode == null) {
			return;
		}
		
		//Set the MMRSet parent to NULL
		mCascadeNode.getMMRSet().setParent(null);
		
		//Clear from one node up..
		BlockTreeNode clearnode = mCascadeNode.getParent();
		while(clearnode != null) {
			//Clear the TxPoW
			clearnode.getTxPow().clearBody();
			
			//Clear the MMRset
			clearnode.setMMRset(null);
			
			//Get the Parent
			clearnode = clearnode.getParent();
		}
	}
	
	
	/**
	 * Get the Chain Speed..
	 * 
	 * Calculated as the different between the cascade node and the tip..
	 */
	public MiniNumber getChainSpeed() {
		//Calculate to seconds..
		MiniNumber start      = mCascadeNode.getTxPow().getTimeSecs();
		MiniNumber end        = mTip.getTxPow().getTimeSecs();
		MiniNumber timediff   = end.sub(start);
		
		//How many blocks..
		MiniNumber blockstart = mCascadeNode.getTxPow().getBlockNumber();
		MiniNumber blockend   = mTip.getTxPow().getBlockNumber();
		MiniNumber blockdiff  = blockend.sub(blockstart); 
		
		//So.. 
		if(timediff.isEqual(MiniNumber.ZERO)) {
			return MiniNumber.ONE;
		}
		MiniNumber speed    = blockdiff.div(timediff);
		
		return speed;
	}
	
	/**
	 * Get the current average difficulty
	 */
	public BigInteger getAvgChainDifficulty() {
		//The Total..
		BigInteger total = new BigInteger("0");
		
		//Cycle back from the tip..
		MiniData casc 			= mCascadeNode.getTxPowID();
		BlockTreeNode current 	= mTip;
		int num=0;
		while(current != null) {
			//Add to the total
			total = total.add(current.getTxPow().getBlockDifficulty().getDataValue());
			num++;
			
			if(current.getTxPowID().isEqual(casc)) {
				//It's the final node.. quit
				break;
			}
			
			//Get thew parent
			current = current.getParent();
		}
		
		if(num == 0) {
			return BigInteger.ZERO;
		}
		
		//The Average
		BigInteger avg = total.divide(new BigInteger(""+num));
		
		return avg;
	}

	public void clearTree() {
		mRoot 			= null;
		mTip 			= null;
		mCascadeNode 	= null;
	}

	public static TxPoW createRandomTxPow() {
		TxPoW txpow = new TxPoW();
		txpow.setHeaderBodyHash();
		txpow.calculateTXPOWID();
		
		return txpow;
	}
	
	public static void main(String[] zArgs) {
		
		BlockTree tree = new BlockTree();
		
		TxPoW root = createRandomTxPow();
		BlockTreeNode rootnode = new BlockTreeNode(root);
		tree.setTreeRoot(rootnode);
		System.out.println("root : "+rootnode.getTxPowID().to0xString(10));
		
		//2 kids..
		TxPoW child = createRandomTxPow();
		BlockTreeNode treenode = new BlockTreeNode(child);
		rootnode.addChild(treenode);
		System.out.println("rootchild1 : "+treenode.getTxPowID().to0xString(10));
		
		TxPoW child4 = createRandomTxPow();
		BlockTreeNode treenode4 = new BlockTreeNode(child4);
		treenode.addChild(treenode4);
		System.out.println("child1child1 : "+treenode4.getTxPowID().to0xString(10));
		
		TxPoW child5 = createRandomTxPow();
		BlockTreeNode treenode5 = new BlockTreeNode(child5);
		treenode.addChild(treenode5);
		System.out.println("child1child2 : "+treenode5.getTxPowID().to0xString(10));
		
		TxPoW child6 = createRandomTxPow();
		BlockTreeNode treenode6 = new BlockTreeNode(child6);
		treenode.addChild(treenode6);
		System.out.println("child1child3 : "+treenode6.getTxPowID().to0xString(10));
		
		TxPoW child2 = createRandomTxPow();
		BlockTreeNode treenode2 = new BlockTreeNode(child2);
		rootnode.addChild(treenode2);
		System.out.println("rootchild2 : "+treenode2.getTxPowID().to0xString(10));
		
		TxPoW child3 = createRandomTxPow();
		BlockTreeNode treenode3 = new BlockTreeNode(child3);
		treenode2.addChild(treenode3);
		System.out.println("child2child1 : "+treenode3.getTxPowID().to0xString(10));
		
		tree.printTree();
		System.out.println();
		
		//Lets copy..
		BlockTreeNode copy = BlockTree.copyTreeNode(rootnode);
		BlockTree copytree = new BlockTree();
		copytree.setTreeRoot(copy);
	
		copytree.printTree();
		
		
		//Search for the child..
//		System.out.println("\nSearch for "+child3.getTxPowID().to0xString(10)+"\n\n");
//		BlockTreeNode find =  tree.findNode(child3.getTxPowID());
//		BlockTreeNode find =  tree.findNode(MiniData.getRandomData(5));
		
		
	}
	
	
}
