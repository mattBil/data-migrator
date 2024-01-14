package net.osgiliath.migrator.core.modelgraph;

/*-
 * #%L
 * data-migrator-core
 * %%
 * Copyright (C) 2024 Osgiliath Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import net.osgiliath.migrator.core.api.metamodel.model.FieldEdge;
import net.osgiliath.migrator.core.api.metamodel.model.MetamodelVertex;
import net.osgiliath.migrator.core.api.model.ModelElement;
import net.osgiliath.migrator.core.api.sourcedb.EntityImporter;
import net.osgiliath.migrator.core.configuration.beans.GraphTraversalSourceProvider;
import net.osgiliath.migrator.core.modelgraph.model.*;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ModelGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(ModelGraphBuilder.class);
    public static final String MODEL_GRAPH_VERTEX_ENTITY_ID = "id";
    public static final String MODEL_GRAPH_VERTEX_METAMODEL_VERTEX = "metamodelVertex";
    public static final String MODEL_GRAPH_VERTEX_ENTITY = "entity";
    public static final String MODEL_GRAPH_EDGE_METAMODEL_FIELD = "field";
    public static final String MODEL_GRAPH_EDGE_METAMODEL_FIELD_NAME = "field_name";
    private final EntityImporter entityImporter;
    private final GraphTraversalSourceProvider graphTraversalSource;

    public ModelGraphBuilder(EntityImporter entityImporter, GraphTraversalSourceProvider graphTraversalSource) {
        this.entityImporter = entityImporter;
        this.graphTraversalSource = graphTraversalSource;
    }

    @Transactional(transactionManager = "sourceTransactionManager", readOnly = true)
    public GraphTraversalSource modelGraphFromMetamodelGraph(org.jgrapht.Graph<MetamodelVertex, FieldEdge> entityMetamodelGraph) {
        log.info("Creating model vertex");
        GraphTraversalSource graphTraversalSource = this.graphTraversalSource.getGraph();
        createVertices(entityMetamodelGraph.vertexSet(), graphTraversalSource);
        log.info("Creating model edges");
        createEdges(graphTraversalSource, entityMetamodelGraph);
        return graphTraversalSource;
    }

    private void createEdges(GraphTraversalSource modelGraph, org.jgrapht.Graph<MetamodelVertex, FieldEdge> entityMetamodelGraph) {
        GraphTraversal<Vertex, Vertex> entities = modelGraph.V();
        List<Vertex> list = entities.toList();
        list.stream().flatMap(v -> {
                    TinkerVertex modelVertex = (TinkerVertex) v;
                    MetamodelVertex metamodelVertex = v.value(MODEL_GRAPH_VERTEX_METAMODEL_VERTEX);
                    log.info("looking for edges for vertex of type {} with id {}", metamodelVertex.getTypeName(), v.value(MODEL_GRAPH_VERTEX_ENTITY_ID));
                    Collection<FieldEdge> edges = metamodelVertex.getOutboundFieldEdges(entityMetamodelGraph).stream().collect(Collectors.toList());
                    return edges.stream().map(edge ->
                            new FieldEdgeTargetVertices(edge, relatedVerticesOfOutgoingEdgeFromModelElementRelationship(modelVertex, edge, modelGraph))
                    ).map(edgeAndTargetVertex ->
                            new SourceVertexFieldEdgeAndTargetVertices(modelVertex, edgeAndTargetVertex));
                })
                .flatMap(edgeAndTargetVertex -> edgeAndTargetVertex.getTargetVertices().stream().map(targetVertex -> new SourceVertexEdgeAndTargetVertex(edgeAndTargetVertex, targetVertex)))
                .forEach(sourceVertexEdgeAndTargetVertex ->
                        sourceVertexEdgeAndTargetVertex.getSourceVertex().addEdge(sourceVertexEdgeAndTargetVertex.getEdge().getFieldName(), sourceVertexEdgeAndTargetVertex.getTargetVertex()).property(MODEL_GRAPH_EDGE_METAMODEL_FIELD, sourceVertexEdgeAndTargetVertex.getEdge().getMetamodelField())
                );
    }

    private Collection<Vertex> relatedVerticesOfOutgoingEdgeFromModelElementRelationship(TinkerVertex modelVertex, FieldEdge edge, GraphTraversalSource modelGraph) {
        log.debug("looking for related vertices for edge {}", edge);
        ModelElement modelElement = modelVertex.value(MODEL_GRAPH_VERTEX_ENTITY);
        Object targetModelElements = modelElement.getEdgeValueFromModelElementRelationShip(edge, modelGraph);
        if (targetModelElements instanceof Collection) {
            return ((Collection<ModelElement>) targetModelElements).stream().map(targetModelElement ->
                    targetEdgeVertexOrEmpty(edge, getTargetEntityId(edge, targetModelElement), modelGraph)
            ).filter(Optional::isPresent).map(Optional::get).toList();
        } else {
            if (null != targetModelElements) {
                return Stream.of(targetEdgeVertexOrEmpty(edge, getTargetEntityId(edge, (ModelElement) targetModelElements), modelGraph))
                        .filter(Optional::isPresent).map(Optional::get).toList();
            }
        }
        return Collections.EMPTY_LIST;
    }

    private Object getTargetEntityId(FieldEdge edge, ModelElement targetEntity) {
        return targetEntity.getId(edge.getTarget());
    }

    private Optional<Vertex> targetEdgeVertexOrEmpty(FieldEdge edge, Object targetEntityId, GraphTraversalSource modelGraph) {
        if (null != targetEntityId) {
            return Optional.of(targetEdgeVertex(edge, targetEntityId, modelGraph));
        }
        return Optional.empty();
    }

    private Vertex targetEdgeVertex(FieldEdge edge, Object relatedEntityId, GraphTraversalSource modelGraph) {
        String targetVertexType = edge.getTarget().getTypeName();
        log.debug("looking for related target vertex type {} with id value {}", targetVertexType, relatedEntityId);
        return modelGraph.V()
                .has(targetVertexType,
                        MODEL_GRAPH_VERTEX_ENTITY_ID,
                        relatedEntityId)
                .property(MODEL_GRAPH_EDGE_METAMODEL_FIELD, edge.getFieldName())
                .next();
    }

    private void createVertices(Set<MetamodelVertex> metamodelVertices, GraphTraversalSource modelGraph) {
        metamodelVertices.stream()
                .map(mv -> new MetamodelVertexAndModelElements(mv, entityImporter.importEntities(mv, new ArrayList<>())))
                .flatMap(mvae -> mvae.getEntities().stream().map(entity -> new MetamodelVertexAndModelElement(mvae.getMetamodelVertex(), entity)))
                .forEach(
                        mvae -> {
                            Object entityId = mvae.getModelElement().getId(mvae.getMetamodelVertex());
                            GraphTraversal traversal = modelGraph
                                    .addV(mvae.getMetamodelVertex().getTypeName())
                                    .property(MODEL_GRAPH_VERTEX_ENTITY_ID, entityId)
                                    .property(MODEL_GRAPH_VERTEX_METAMODEL_VERTEX, mvae.getMetamodelVertex())
                                    .property(MODEL_GRAPH_VERTEX_ENTITY, mvae.getModelElement());
                            mvae.getMetamodelVertex().getAdditionalModelVertexProperties(mvae.getModelElement()).forEach((k, v) -> traversal.property(k, v));
                            traversal.next();
                        });
    }
}
