package edu.ucsf.rbvi.boundaryLayout.internal.algorithms;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;

public class BoundaryTreeNode {
	Rectangle2D.Double entry;
	BoundaryTreeNode parent;
	Map<String, BoundaryTreeNode> children;
	static final String LEFTCHILD = "LEFT";
	static final String TOPCHILD = "TOP";
	static final String RIGHTCHILD = "RIGHT";
	static final String BOTTOMCHILD = "BOTTOM";

	/*
	 * Construct a BoundaryTreeNode with a specified entry: children and parent are null.
	 * 
	 */
	BoundaryTreeNode(Rectangle2D.Double entry) {
		this(entry, null, null, null, null, null);
	}

	/*
	 *  Construct a BoundaryTreeNode with a specified entry and parent: children
	 *  are null.
	 */
	BoundaryTreeNode(Rectangle2D.Double entry, BoundaryTreeNode parent) {
		this(entry, parent, null, null, null, null);
	}

	/**
	 *  Construct a BoundaryTreeNode, specifying entry, parent and children.
	 **/
	BoundaryTreeNode(Rectangle2D.Double entry, BoundaryTreeNode parent,
			BoundaryTreeNode leftChild, BoundaryTreeNode topChild, 
			BoundaryTreeNode rightChild, BoundaryTreeNode bottomChild) {
		children = new HashMap<>();
		children.put(LEFTCHILD, leftChild);
		children.put(TOPCHILD, topChild);
		children.put(RIGHTCHILD, rightChild);
		children.put(BOTTOMCHILD, bottomChild);
		this.entry = entry;
		this.parent = parent;
	}

	/**
	 *  Express a BoundaryTreeNode as a String.
	 *
	 *  @return a String representing the BoundaryTreeNode.
	 **/
	@Override
	public String toString() {
		String s = "";
		s = "The location is (" + entry.getX() + "," + entry.getY() + ")";
		s = s + "\nThe dimensions are (w,h): " + entry.getWidth() + "," + entry.getHeight();
		s = s + "\nThe children are (L,T,R,B): ";
		s += "(" + children.get(LEFTCHILD) + "," + children.get(TOPCHILD) + "," + 
				children.get(RIGHTCHILD) + "," + children.get(BOTTOMCHILD) + ")"; 
		return s;
	}
	
	/*
	 * Checks to see if this node has children
	 * 
	 * @return true if this node has at least 1 child
	 * */
	public boolean hasChildren() {
		return (children.get(LEFTCHILD) != null || 
				children.get(TOPCHILD) != null || 
				children.get(RIGHTCHILD) != null || 
				children.get(BOTTOMCHILD) != null);
	}
	
	/*
	 * Adds children to this node
	 * 
	 * @param BoundaryTreeNode[] partitionChildren is assumed to be of
	 * length 4 holding [leftChild, topChild, rightChild, bottomChild]
	 * in that order
	 * */
	public void addChildren(BoundaryTreeNode[] partitionChildren) {
		children.put(LEFTCHILD, partitionChildren[0]);
		children.put(TOPCHILD, partitionChildren[1]);
		children.put(RIGHTCHILD, partitionChildren[2]);
		children.put(BOTTOMCHILD, partitionChildren[3]);
	}
}