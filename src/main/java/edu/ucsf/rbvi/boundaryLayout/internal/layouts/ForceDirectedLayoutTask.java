package edu.ucsf.rbvi.boundaryLayout.internal.layouts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import  java.awt.geom.Point2D;
import  java.awt.geom.Rectangle2D;

import org.cytoscape.model.CyEdge;
import org.cytoscape.model.CyNode;
import org.cytoscape.service.util.CyServiceRegistrar;
import org.cytoscape.view.layout.AbstractLayoutTask;

import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.View;
import org.cytoscape.view.presentation.annotations.Annotation;
import org.cytoscape.view.presentation.annotations.AnnotationManager;
import org.cytoscape.view.presentation.annotations.ShapeAnnotation;
import org.cytoscape.view.presentation.property.BasicVisualLexicon;

import org.cytoscape.work.TaskMonitor;
import org.cytoscape.work.undo.UndoSupport;

import edu.ucsf.rbvi.boundaryLayout.internal.algorithms.BoundaryContainsAlgorithm;
import edu.ucsf.rbvi.boundaryLayout.internal.algorithms.BoundaryTree;
import prefuse.util.force.DragForce;
import prefuse.util.force.ForceItem;
import prefuse.util.force.ForceSimulator;
import prefuse.util.force.NBodyForce;
import prefuse.util.force.RectangularWallForce;
import prefuse.util.force.SpringForce;

public class ForceDirectedLayoutTask extends AbstractLayoutTask {
	private ForceDirectedLayout.Integrators integrator;
	private Map<CyNode,ForceItem> forceItems;
	private ForceDirectedLayoutContext context;
	private CyServiceRegistrar registrar;
	private final List<View<CyNode>> nodeViewList;
	private final List<View<CyEdge>> edgeViewList;
	private final String chosenCategory;
	final CyNetworkView netView;
	private Map<Object, BoundaryAnnotation> boundaries;
	private Rectangle2D unionOfBoundaries;
	private TaskMonitor taskMonitor;

	public ForceDirectedLayoutTask( final String displayName,
			final CyNetworkView netView,
			final Set<View<CyNode>> nodesToLayOut,
			final ForceDirectedLayoutContext context,
			final String layoutAttribute,
			final ForceDirectedLayout.Integrators integrator,
			final CyServiceRegistrar registrar, 
			final UndoSupport undo) {
		super(displayName, netView, nodesToLayOut, layoutAttribute, undo);

		if (nodesToLayOut != null && nodesToLayOut.size() > 0)
			nodeViewList = new ArrayList<>(nodesToLayOut);
		else
			nodeViewList = new ArrayList<>(netView.getNodeViews());

		edgeViewList = new ArrayList<>(netView.getEdgeViews());

		this.netView = netView;
		this.context = context;
		this.integrator = integrator;
		this.registrar = registrar;
		this.chosenCategory = layoutAttribute;
		// We don't want to recenter or we'll move all of our nodes away from the annotations
		recenter = false; // This is provided by AbstractLayoutTask

		getBoundaries();
		this.unionOfBoundaries = null;
		
		forceItems = new HashMap<CyNode, ForceItem>();

		if (boundaries == null && layoutAttribute != null) 
			boundaries = AutoMode.createAnnotations(netView, nodeViewList, layoutAttribute, registrar);

		if(context.gravConst < 0)
			context.gravConst *= -1;
	}

	// TODO: I think it would be better to layout each group in separate passes
	// Um, maybe not...
	@Override
	protected void doLayout(TaskMonitor taskMonitor) {
		this.taskMonitor = taskMonitor;

		if (boundaries != null) {
			for(BoundaryAnnotation boundary : boundaries.values()) 
				initNodeLocations(boundary);
			unionOfBoundaries = this.getUnionofBoundaries();
		}

		ForceSimulator m_fsim = new ForceSimulator();
		m_fsim.speedLimit = context.speedLimit;
		m_fsim.addForce(new NBodyForce(context.avoidOverlap, context.overlapForce));
		m_fsim.addForce(new SpringForce());
		m_fsim.addForce(new DragForce());
		forceItems.clear();

		if(boundaries != null) 
			for(BoundaryAnnotation boundary : boundaries.values()) 
				addAnnotationForce(m_fsim, boundary);

		// initialize node locations and properties
		for (View<CyNode> nodeView : nodeViewList) {
			ForceItem fitem = forceItems.get(nodeView.getModel()); 
			if (fitem == null) {
				fitem = new ForceItem();
				forceItems.put(nodeView.getModel(), fitem);
			}

			fitem.mass = (float) context.defaultNodeMass;

			Object group = null;
			if(chosenCategory != null)
				group = netView.getModel().getRow(nodeView.getModel()).getRaw(chosenCategory);
			
			double width = nodeView.getVisualProperty(BasicVisualLexicon.NODE_WIDTH);
			double height = nodeView.getVisualProperty(BasicVisualLexicon.NODE_HEIGHT);
			fitem.dimensions[0] = (float) width;
			fitem.dimensions[1] = (float) height;
			fitem.category = group;

			if(boundaries != null && group != null) {
				if(boundaries.keySet().contains(group)) {
					BoundaryAnnotation boundary = boundaries.get(group);
					Point2D initPosition = boundary.getRandomNodeInit();
					double thisArea = (double) (fitem.dimensions[0] * fitem.dimensions[1]) * 2.;
					System.out.println(boundary.getName() + " add " + thisArea);
					boundary.addNodeArea(thisArea);
					fitem.location[0] = (float) initPosition.getX(); 
					fitem.location[1] = (float) initPosition.getY(); 
				} else if(unionOfBoundaries != null) {
					fitem.location[0] = (float) (unionOfBoundaries.getX() - (unionOfBoundaries.getWidth() / 4));
					fitem.location[1] = (float) (unionOfBoundaries.getY() + (unionOfBoundaries.getHeight() / 2));
				} 
			}
			
			m_fsim.addItem(fitem);
		}
		
		System.out.println(context.scaleNodes + " - scale the nodes");
		if(context.scaleNodes)
			nodeBoundaryScale();

		// initialize edges
		for (View<CyEdge> edgeView : edgeViewList) {
			CyEdge edge = edgeView.getModel();
			CyNode n1 = edge.getSource();
			ForceItem f1 = forceItems.get(n1); 
			CyNode n2 = edge.getTarget();
			ForceItem f2 = forceItems.get(n2); 
			if ( f1 == null || f2 == null )
				continue;
			m_fsim.addSpring(f1, f2, (float) context.defaultSpringCoefficient, 
					(float) context.defaultSpringLength); 
		}

		final int checkCenter = (context.numIterations / 15) + 1;

		// perform layout
		long timestep = 1000L;
		for ( int i = 0; i < context.numIterations && !cancelled; i++ ) {
			timestep *= (1.0 - i/(double)context.numIterations);
			long step = timestep + 50;
			m_fsim.runSimulator(step);
			if(i % checkCenter == 0) 
				checkCenter(m_fsim);
			taskMonitor.setProgress((int)(((double)i/(double)context.numIterations)*90.+5));
		}
		checkCenter(m_fsim);

		// update positions
		for (CyNode node : forceItems.keySet()) {
			ForceItem fitem = forceItems.get(node); 
			View<CyNode> nodeView = netView.getNodeView(node);
			nodeView.setVisualProperty(BasicVisualLexicon.NODE_X_LOCATION, (double)fitem.location[0]);
			nodeView.setVisualProperty(BasicVisualLexicon.NODE_Y_LOCATION, (double)fitem.location[1]);
		}
	}

	private void checkCenter(ForceSimulator m_fsim) {
		if(boundaries.size() != 0) {
			Iterator itemsIterator = m_fsim.getItems();
			while(itemsIterator.hasNext()) {
				ForceItem nextItem = (ForceItem) itemsIterator.next();
				Rectangle2D bbox = new Rectangle2D.Double((double) nextItem.location[0], (double) nextItem.location[1], 
						nextItem.dimensions[0], nextItem.dimensions[1]);
				if(boundaries.containsKey(nextItem.category)) {
					BoundaryAnnotation nextBoundary = boundaries.get(nextItem.category);
					int moveDir = RectangularWallForce.IN_PROJECTION;
					if(!contains(nextBoundary, bbox, moveDir)) {
						// We moved the node outside of the shape
						// Find the closest point in the bounding box and move back
						nextBoundary.newProjection(moveDir);
						Point2D nearestPoint = getNearestPoint(nextBoundary, bbox, moveDir);
						setItemLoc(nextItem, nearestPoint);
					} 

					//look at each intersecting shape annotation and project accordingly
					moveDir = RectangularWallForce.OUT_PROJECTION;
					if(nextBoundary.hasIntersections()) {
						for(BoundaryAnnotation intersectingBoundary : nextBoundary.getIntersections()) {
							if(contains(intersectingBoundary, bbox, moveDir)) {
								Point2D nearestPoint = getNearestPoint(intersectingBoundary, bbox, moveDir);
								setItemLoc(nextItem, nearestPoint);
								
								intersectingBoundary.newProjection(moveDir);
								bbox = new Rectangle2D.Double((double) nextItem.location[0], (double) nextItem.location[1],
										nextItem.dimensions[0], nextItem.dimensions[1]);
								if(!contains(nextBoundary, bbox, moveDir)) {
									nextBoundary.newProjection(moveDir);
									setItemLoc(nextItem, nextBoundary.getRandomNodeInit());
								}
							}
						}
					}
				}
				else {
					int moveDir = RectangularWallForce.OUT_PROJECTION;
					for(BoundaryAnnotation boundary : boundaries.values()) {
						if(contains(boundary, bbox, -1)) {
							Point2D nearestPoint = getNearestPoint(boundary, bbox, moveDir);
							setItemLoc(nextItem, nearestPoint);
							boundary.newProjection(moveDir);
						}
					}
					if(unionOfBoundaries != null && unionOfBoundaries.contains(new Point2D.Double(bbox.getX(), bbox.getY()))) {
						nextItem.location[0] = (float) (unionOfBoundaries.getX() - (unionOfBoundaries.getWidth() / 4));
						nextItem.location[1] = (float) (unionOfBoundaries.getY() + (unionOfBoundaries.getHeight() / 2));
						nextItem.plocation[0] = nextItem.location[0];
						nextItem.plocation[1] = nextItem.location[1];
					}
				}
			}
		}
	}

	private boolean contains(BoundaryAnnotation boundary, Rectangle2D nodebbox, int moveDir) {
		boolean contained = true;
		ShapeAnnotation shape = boundary.getShapeAnnotation();
		Rectangle2D shapeBox = boundary.getBoundingBox();
		double[] diffVector = { Math.abs(nodebbox.getX() - shapeBox.getCenterX()), Math.abs(nodebbox.getY() - shapeBox.getCenterY())};
		diffVector[0] += nodebbox.getWidth() / 2 * moveDir;
		diffVector[1] += nodebbox.getHeight() / 2 * moveDir;

		switch(shape.getShapeType()) {
		case "Rounded Rectangle":
		case "Rectangle": 
			if(diffVector[0] > shapeBox.getWidth() / 2. || diffVector[1] > shapeBox.getHeight() / 2.) 
				contained = false;
			break;

		case "Ellipse":
			double xVec = (diffVector[0] * diffVector[0]) / (shapeBox.getWidth() * shapeBox.getWidth() / 4.);
			double yVec = (diffVector[1] * diffVector[1]) / (shapeBox.getHeight() * shapeBox.getHeight() / 4.);
			if(xVec + yVec >= 1)
				contained = false;
			break;
		}
		return contained;
	}

	private void setItemLoc(ForceItem item, Point2D loc) {
		item.location[0] = (float) loc.getX();
		item.location[1] = (float) loc.getY();
		item.plocation[0] = item.location[0];
		item.plocation[1] = item.location[1];
	}

	private Point2D getNearestPoint(BoundaryAnnotation boundary, Rectangle2D bbox, int moveDir) {
		Rectangle2D shapeBox = boundary.getBoundingBox();
		double[] diffVector = { bbox.getX() - shapeBox.getCenterX(), bbox.getY() - shapeBox.getCenterY()};
		diffVector[0] += bbox.getWidth() / 2 * moveDir * (diffVector[0] < 0 ? -1 : 1);
		diffVector[1] += bbox.getHeight() / 2 * moveDir * (diffVector[1] < 0 ? -1 : 1);
		
		double scale = 1.;
		switch(boundary.getShapeAnnotation().getShapeType()) {
		case "Rounded Rectangle":
		case "Rectangle":
			scale = getScaleRectangle(shapeBox, diffVector);
			break;
			
		case "Ellipse":
			scale = getScaleEllipse(shapeBox, bbox, diffVector);
			break;
		}	

		diffVector[0] = diffVector[0] * scale; 
		diffVector[1] = diffVector[1] * scale;
		diffVector[0] -= bbox.getWidth() / 2 * moveDir * (diffVector[0] < 0 ? -1 : 1);
		diffVector[1] -= bbox.getHeight() / 2 * moveDir * (diffVector[1] < 0 ? -1 : 1);

		return new Point2D.Double(shapeBox.getCenterX() + diffVector[0], shapeBox.getCenterY() + diffVector[1]);
	}

	private double getScaleRectangle(Rectangle2D shape, double[] diffVector) {
		double scale = 1.;
		//if top or bottom are sides are closer -> scale based on height, otherwise based on width
		if(shape.getHeight() / shape.getWidth() <= Math.abs(diffVector[1] / diffVector[0])) 
			scale = (shape.getHeight() / 2) / Math.abs(diffVector[1]);
		else 
			scale = (shape.getWidth() / 2) / Math.abs(diffVector[0]);
		return scale;
	}
	
	private double getScaleEllipse(Rectangle2D shape, Rectangle2D bbox, double[] diffVector) { 
		double scale = 1.;
		double xVec = (diffVector[0] * diffVector[0]) / (shape.getWidth() * shape.getWidth() / 4.);
		double yVec = (diffVector[1] * diffVector[1]) / (shape.getHeight() * shape.getHeight() / 4.);
		scale = 1 / Math.sqrt(xVec + yVec);
		return scale;
	}

	/* @return the HashMap shapeAnnotations which consists of 
	 * all of the Shape Annotations in the current network view and
	 * maps them to their respective name. null is returned if
	 * the user did not create any Shape Annotations, which would
	 * means AutoMode must be run.
	 * */
	protected void getBoundaries() {
		List<Annotation> annotations = registrar.getService(AnnotationManager.class).getAnnotations(netView);
		if(boundaries == null)
			boundaries = new HashMap<>();
		if(annotations != null) {
			for(Annotation annotation : annotations) {
				if(annotation instanceof ShapeAnnotation) {
					ShapeAnnotation shapeAnnotation = (ShapeAnnotation) annotation;
					BoundaryAnnotation boundary = new BoundaryAnnotation(shapeAnnotation);
					boundaries.put(shapeAnnotation.getName(), boundary);
				}
			}
		}
	}

	/* @param m_fsim is the ForceSimulator instance that this
	 * added force belongs to.
	 * @param shapeAnnotation stores an existing ShapeAnnotation.
	 * This method adds the wall force associated with shapeAnnotation parameter.
	 * */
	protected void addAnnotationForce(ForceSimulator m_fsim, BoundaryAnnotation boundary) {
		Point2D annotationDimensions = getAnnotationDimensions(boundary);
		Point2D annotationCenter = getAnnotationCenter(boundary);
		if(boundary.getShapeAnnotation().getShapeType().equals("Ellipse")) { //only want inner rectangle
			double newWidth = annotationDimensions.getX() * Math.sqrt(2) / 2;
			double newHeight = annotationDimensions.getY() * Math.sqrt(2) / 2;
			annotationDimensions.setLocation(newWidth, newHeight);
		} 
		RectangularWallForce wall = new RectangularWallForce(annotationCenter, annotationDimensions, 
				context.gravConst, context.variableWallForce);
		boundary.setWallForce(wall);
		m_fsim.addForce(wall);
	}

	/* @param shapeAnnotation stores an existing ShapeAnnotation.
	 * @return the Point2D dimensions of shapeAnnotation where 
	 * the x value of the point holds the width and the y value of the 
	 * point holds the height.
	 * */
	private Point2D getAnnotationDimensions(BoundaryAnnotation boundary) {
		Rectangle2D bb = boundary.getBoundingBox();
		return new Point2D.Double(bb.getWidth(), bb.getHeight());
	}

	/* @param shapeAnnotation stores an existing ShapeAnnotation.
	 * @return the Point2D location where the center of the shapeAnnotation
	 * is.
	 * */
	private Point2D getAnnotationCenter(BoundaryAnnotation boundary) { 
		Rectangle2D bb = boundary.getBoundingBox();
		return new Point2D.Double(bb.getX() + bb.getWidth() / 2, bb.getY() + bb.getHeight() / 2);
	}

	/* @param shapeAnnotation stores an existing ShapeAnnotation.
	 * This method calculates and initializes a HashMap of key ShapeAnnotation
	 * and value Point2D where the Point2D is the location
	 * where all of the nodes of that respective shape annotation are to be
	 * initialized.
	 * */
	private void initNodeLocations(BoundaryAnnotation boundary) { 
		Rectangle2D boundingBox = boundary.getBoundingBox();
		List<Rectangle2D> applySpecialInitialization = applySpecialInitialization(boundary, boundingBox);
		double xCenter = boundingBox.getX() + boundingBox.getWidth() / 2.;
		double yCenter = boundingBox.getY() + boundingBox.getHeight() / 2.;
		List<Point2D> initNodes = new ArrayList<>();
		initNodes.add(new Point2D.Double(xCenter, yCenter));
		double totalArea = boundingBox.getWidth() * boundingBox.getHeight();
		System.out.println("The original area is " + totalArea);
		if(!applySpecialInitialization.isEmpty()) {
			initNodes.remove(0);
			List<Rectangle2D> initRectangles = BoundaryContainsAlgorithm.doAlgorithm(
					boundingBox, applySpecialInitialization);
			totalArea = BoundaryTree.getTotalArea(initRectangles);
			for(Rectangle2D initRectangle : initRectangles) {
				xCenter = initRectangle.getX() + initRectangle.getWidth() / 2.;
				yCenter = initRectangle.getY() + initRectangle.getHeight() / 2.;
				initNodes.add(new Point2D.Double(xCenter, yCenter));
			}
		}
		boundary.setScalableArea(totalArea);
		boundary.setInitializations(initNodes);
	}

	/* @param shapeAnnotation stores an existing ShapeAnnotation.
	 * @param boundingBox stores the Rectangle2D of the shapeAnnotation.
	 * @return list of Rectangle2D's of shape annotations that boundingBox
	 * contains.
	 * */
	private List<Rectangle2D> applySpecialInitialization(BoundaryAnnotation boundary, Rectangle2D boundingBox) {
		List<Rectangle2D> listOfContainments = new ArrayList<>();
		for(BoundaryAnnotation comparedBoundary : boundaries.values()) {
			Rectangle2D comparedBoundingBox = comparedBoundary.getBoundingBox();
			if(comparedBoundary.getName().equals(boundary.getName())) {} 
			else if(boundingBox.intersects(comparedBoundingBox) && !comparedBoundingBox.contains(boundingBox))  {
				listOfContainments.add(comparedBoundingBox);
				boundary.addIntersection(comparedBoundary);
			}
		}
		return listOfContainments;
	}

	private Rectangle2D getUnionofBoundaries() {
		if(boundaries.size() == 0)
			return null;
		Rectangle2D unionOfBoundaries = new Rectangle2D.Double();
		for(BoundaryAnnotation newBoundary : this.boundaries.values()) 
			unionOfBoundaries.setRect(unionOfBoundaries.createUnion(newBoundary.getBoundingBox()));
		return unionOfBoundaries;
	}
	
	private void nodeBoundaryScale() {
		Map<Object, Double> nodeScalesMap = new HashMap<>();
		
		for(View<CyNode> nodeView : nodeViewList) {
			ForceItem fitem = forceItems.get(nodeView.getModel()); 
			
			double nodeScale = 1.;
			if(!nodeScalesMap.containsKey(fitem.category)) {
				nodeScale = boundaries.get(fitem.category).getNodeScale();
				nodeScalesMap.put(fitem.category, nodeScale);
			} else {
				nodeScale = nodeScalesMap.get(fitem.category);
			}
			
			double dimScale = Math.sqrt(nodeScale);
			fitem.dimensions[0] = fitem.dimensions[0] * (float) dimScale;
			fitem.dimensions[1] = fitem.dimensions[1] * (float) dimScale;
			nodeView.setVisualProperty(BasicVisualLexicon.NODE_WIDTH, (double) fitem.dimensions[0]);
			nodeView.setVisualProperty(BasicVisualLexicon.NODE_HEIGHT, (double) fitem.dimensions[1]);
		}
	}
}
