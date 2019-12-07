/*
 * Copyright (C) 2019 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration.
 * All Rights Reserved.
 */

package gov.nasa.worldwind.ogc.kml.impl;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.event.Message;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.ogc.kml.KMLAbstractFeature;
import gov.nasa.worldwind.ogc.kml.KMLAbstractGeometry;
import gov.nasa.worldwind.ogc.kml.KMLAbstractObject;
import gov.nasa.worldwind.ogc.kml.KMLAbstractSubStyle;
import gov.nasa.worldwind.ogc.kml.KMLConstants;
import gov.nasa.worldwind.ogc.kml.KMLLineStyle;
import gov.nasa.worldwind.ogc.kml.KMLLinearRing;
import gov.nasa.worldwind.ogc.kml.KMLPlacemark;
import gov.nasa.worldwind.ogc.kml.KMLPolyStyle;
import gov.nasa.worldwind.ogc.kml.KMLPolygon;
import gov.nasa.worldwind.pick.PickedObject;
import gov.nasa.worldwind.render.BasicShapeAttributes;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.ExtrudedPolygon;
import gov.nasa.worldwind.render.Material;
import gov.nasa.worldwind.render.ShapeAttributes;
import gov.nasa.worldwind.util.Logging;
import gov.nasa.worldwind.util.WWUtil;

/**
 * @author tag
 * @version $Id: KMLExtrudedPolygonImpl.java 2151 2014-07-15 17:12:46Z tgaskins $
 */
public class KMLExtrudedPolygonImpl extends ExtrudedPolygon implements KMLRenderable
{
    protected final KMLAbstractFeature parent;
    protected boolean highlightAttributesResolved = false;
    protected boolean normalAttributesResolved = false;

    /**
     * Create an instance.
     *
     * @param tc        the current {@link KMLTraversalContext}.
     * @param placemark the <i>Placemark</i> element containing the <i>LineString</i>.
     * @param geom      the {@link KMLPolygon} geometry.
     *
     * @throws NullPointerException     if the geomtry is null.
     * @throws IllegalArgumentException if the parent placemark or the traversal context is null.
     */
    public KMLExtrudedPolygonImpl(KMLTraversalContext tc, KMLPlacemark placemark, KMLAbstractGeometry geom)
    {
        if (tc == null)
        {
            String msg = Logging.getMessage("nullValue.TraversalContextIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (placemark == null)
        {
            String msg = Logging.getMessage("nullValue.ParentIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.parent = placemark;

        KMLPolygon polygon = (KMLPolygon) geom;
        this.setEnableSides(polygon.isExtrude());

        this.setAltitudeMode(WorldWind.CLAMP_TO_GROUND); // KML default
        String altMode = polygon.getAltitudeMode();
        if (!WWUtil.isEmpty(altMode))
        {
            if ("clampToGround".equals(altMode))
                this.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            else if ("relativeToGround".equals(altMode))
                this.setAltitudeMode(WorldWind.RELATIVE_TO_GROUND);
            else if ("absolute".equals(altMode))
                this.setAltitudeMode(WorldWind.ABSOLUTE);
        }

        KMLLinearRing outerBoundary = polygon.getOuterBoundary();
        if (outerBoundary != null)
        {
            Position.PositionList coords = outerBoundary.getCoordinates();
            if (coords != null && coords.list != null)
                this.setOuterBoundary(outerBoundary.getCoordinates().list);
        }

        Iterable<? extends KMLLinearRing> innerBoundaries = polygon.getInnerBoundaries();
        if (innerBoundaries != null)
        {
            for (KMLLinearRing ring : innerBoundaries)
            {
                Position.PositionList coords = ring.getCoordinates();
                if (coords != null && coords.list != null)
                    this.addInnerBoundary(ring.getCoordinates().list);
            }
        }

        if (placemark.getName() != null)
            this.setValue(AVKey.DISPLAY_NAME, placemark.getName());

        if (placemark.getDescription() != null)
            this.setValue(AVKey.DESCRIPTION, placemark.getDescription());

        if (placemark.getSnippetText() != null)
            this.setValue(AVKey.SHORT_DESCRIPTION, placemark.getSnippetText());

        this.setValue(AVKey.CONTEXT, this.parent);
    }

    public void preRender(KMLTraversalContext tc, DrawContext dc)
    {
        super.preRender(dc);
    }

    public void render(KMLTraversalContext tc, DrawContext dc)
    {
        // If the attributes are not inline or internal then they might not be resolved until the external KML
        // document is resolved. Therefore check to see if resolution has occurred.

        if (this.isHighlighted())
        {
            if (!this.highlightAttributesResolved)
            {
                ShapeAttributes a = this.getCapHighlightAttributes();
                if (a == null || a.isUnresolved())
                {
                    a = this.makeAttributesCurrent(KMLConstants.HIGHLIGHT);
                    if (a != null)
                    {
                        this.setCapHighlightAttributes(a);
                        this.setSideHighlightAttributes(a);
                        if (!a.isUnresolved())
                            this.highlightAttributesResolved = true;
                    }
                }
            }
        }
        else
        {
            if (!this.normalAttributesResolved)
            {
                ShapeAttributes a = this.getCapAttributes();
                if (a == null || a.isUnresolved())
                {
                    a = this.makeAttributesCurrent(KMLConstants.NORMAL);
                    if (a != null)
                    {
                        this.setCapAttributes(a);
                        this.setSideAttributes(a);
                        if (!a.isUnresolved())
                            this.normalAttributesResolved = true;
                    }
                }
            }
        }

        this.render(dc);
    }

    /** {@inheritDoc} */
    @Override
    protected PickedObject createPickedObject(int colorCode)
    {
        PickedObject po = super.createPickedObject(colorCode);

        // Add the KMLPlacemark to the picked object as the context of the picked object.
        po.setValue(AVKey.CONTEXT, this.parent);
        return po;
    }

    /**
     * Determine and set the {@link Path} highlight attributes from the KML <i>Feature</i> fields.
     *
     * @param attrType the type of attributes, either {@link KMLConstants#NORMAL} or {@link KMLConstants#HIGHLIGHT}.
     *
     * @return the new attributes.
     */
    protected ShapeAttributes makeAttributesCurrent(String attrType)
    {
        ShapeAttributes attrs = this.getInitialAttributes(
            this.isHighlighted() ? KMLConstants.HIGHLIGHT : KMLConstants.NORMAL);

        // Get the KML sub-style for Line attributes. Map them to Shape attributes.

        KMLAbstractSubStyle lineSubStyle = this.parent.getSubStyle(new KMLLineStyle(null), attrType);
        if (!this.isHighlighted() || KMLUtil.isHighlightStyleState(lineSubStyle))
        {
            KMLUtil.assembleLineAttributes(attrs, (KMLLineStyle) lineSubStyle);
            if (lineSubStyle.hasField(AVKey.UNRESOLVED))
                attrs.setUnresolved(true);
        }

        // Get the KML sub-style for interior attributes. Map them to Shape attributes.

        KMLAbstractSubStyle fillSubStyle = this.parent.getSubStyle(new KMLPolyStyle(null), attrType);
        if (!this.isHighlighted() || KMLUtil.isHighlightStyleState(lineSubStyle))
        {
            KMLUtil.assembleInteriorAttributes(attrs, (KMLPolyStyle) fillSubStyle);
            if (fillSubStyle.hasField(AVKey.UNRESOLVED))
                attrs.setUnresolved(true);

            attrs.setDrawInterior(((KMLPolyStyle) fillSubStyle).isFill());
            attrs.setDrawOutline(((KMLPolyStyle) fillSubStyle).isOutline());
        }

        return attrs;
    }

    protected ShapeAttributes getInitialAttributes(String attrType)
    {
        ShapeAttributes attrs = new BasicShapeAttributes();

        if (KMLConstants.HIGHLIGHT.equals(attrType))
        {
            attrs.setOutlineMaterial(Material.RED);
            attrs.setInteriorMaterial(Material.PINK);
        }
        else
        {
            attrs.setOutlineMaterial(Material.WHITE);
            attrs.setInteriorMaterial(Material.LIGHT_GRAY);
        }

        return attrs;
    }

    @Override
    public void onMessage(Message message)
    {
        super.onMessage(message);

        if (KMLAbstractObject.MSG_STYLE_CHANGED.equals(message.getName()))
        {
            this.normalAttributesResolved = false;
            this.highlightAttributesResolved = false;

            if (this.getAttributes() != null)
                this.getAttributes().setUnresolved(true);
            if (this.getHighlightAttributes() != null)
                this.getHighlightAttributes().setUnresolved(true);
        }
    }
}
