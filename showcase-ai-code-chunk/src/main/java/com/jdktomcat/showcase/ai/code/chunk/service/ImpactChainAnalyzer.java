package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.*;
import com.jdktomcat.showcase.ai.code.chunk.repository.ImpactChainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core service for building and analyzing impact chains.
 * Orchestrates entry point detection, code dependency analysis, and impact chain construction.
 */
@Service
public class ImpactChainAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ImpactChainAnalyzer.class);

    private final EntryPointScanner entryPointScanner;
    private final ImpactChainRepository repository;

    public ImpactChainAnalyzer(
            EntryPointScanner entryPointScanner,
            ImpactChainRepository repository
    ) {
        this.entryPointScanner = entryPointScanner;
        this.repository = repository;
    }

    /**
     * Perform full impact chain analysis:
     * 1. Scan for entry points
     * 2. Analyze code dependencies
     * 3. Build impact chains
     * 4. Store in Neo4j
     */
    public AnalysisResult fullAnalysis() throws IOException {
        log.info("开始完整影响链分析");

        // Step 1: Scan for entry points using pattern matching (fast)
        log.debug("开始扫描入口点");
        List<EntryPoint> entryPoints = entryPointScanner.scan();
        log.info("通过模式扫描找到 {} 个入口点", entryPoints.size());

        // Step 2: Save entry points to Neo4j
        log.debug("保存入口点到 Neo4j count={}", entryPoints.size());
        repository.saveEntryPoints(entryPoints);
        log.info("入口点保存完成");

        // Step 3: Build impact chains for each entry point
        List<ImpactChainNode> allNodes = new ArrayList<>();
        List<ImpactChainRelation> allRelations = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < entryPoints.size(); i++) {
            EntryPoint entryPoint = entryPoints.get(i);
            try {
                log.debug("构建影响链 [{}/{}] entryPointId={}", i + 1, entryPoints.size(), entryPoint.id());
                ImpactChain impactChain = buildImpactChain(entryPoint);
                allNodes.addAll(impactChain.nodes());
                allRelations.addAll(impactChain.relations());
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.warn("构建影响链失败 entryPointId={} error={}", entryPoint.id(), e.getMessage(), e);
            }
        }

        log.debug("影响链构建完成 success={} failure={} rawNodes={} rawRelations={}", 
                successCount, failureCount, allNodes.size(), allRelations.size());

        // Remove duplicates
        List<ImpactChainNode> uniqueNodes = deduplicateNodes(allNodes);
        List<ImpactChainRelation> uniqueRelations = deduplicateRelations(allRelations);

        log.info("影响链去重完成 nodes={} relations={} (去重前 nodes={} relations={})", 
                uniqueNodes.size(), uniqueRelations.size(), allNodes.size(), allRelations.size());

        // Step 4: Save to Neo4j
        log.debug("保存影响链到 Neo4j nodes={} relations={}", uniqueNodes.size(), uniqueRelations.size());
        repository.saveImpactChain(uniqueNodes, uniqueRelations);
        log.debug("链接入口点到代码节点");
        repository.linkEntryPointsToCodeNodes(entryPoints);

        log.info("完整影响链分析完成 entryPoints={} nodes={} relations={}", 
                entryPoints.size(), uniqueNodes.size(), uniqueRelations.size());

        return new AnalysisResult(
                entryPoints.size(),
                uniqueNodes.size(),
                uniqueRelations.size()
        );
    }

    /**
     * Build impact chain for a specific entry point.
     */
    public ImpactChain buildImpactChain(EntryPoint entryPoint) {
        log.debug("Building impact chain for entry point: {}", entryPoint.id());

        List<ImpactChainNode> nodes = new ArrayList<>();
        List<ImpactChainRelation> relations = new ArrayList<>();

        // Create entry point node
        ImpactChainNode entryNode = new ImpactChainNode(
                entryPoint.id(),
                NodeType.METHOD,
                entryPoint.methodName(),
                entryPoint.methodSignature(),
                "ENTRY_METHOD",
                entryPoint.filePath(),
                entryPoint.module(),
                entryPoint.lineNumber(),
                entryPoint.lineNumber(),
                true,
                entryPoint.type()
        );
        nodes.add(entryNode);

        // Build call chain based on entry point type
        switch (entryPoint.type()) {
            case HTTP -> buildHttpImpactChain(entryPoint, nodes, relations);
            case RPC -> buildRpcImpactChain(entryPoint, nodes, relations);
            case MQ -> buildMqImpactChain(entryPoint, nodes, relations);
            case SCHEDULED -> buildScheduledImpactChain(entryPoint, nodes, relations);
            case EVENT -> buildEventImpactChain(entryPoint, nodes, relations);
        }

        return new ImpactChain(nodes, relations);
    }

    private void buildHttpImpactChain(EntryPoint entryPoint, List<ImpactChainNode> nodes, List<ImpactChainRelation> relations) {
        // HTTP entry points typically follow: Controller -> Service -> Repository
        String controllerType = entryPoint.className();

        // Add controller type node
        ImpactChainNode controllerNode = createTypeNode(controllerType, entryPoint.filePath(), entryPoint.module());
        nodes.add(controllerNode);
        relations.add(new ImpactChainRelation(entryPoint.id(), controllerNode.id(), RelationType.DECLARES, null));

        // Simulate service layer discovery (in real implementation, use AST analysis)
        String serviceName = deriveServiceName(controllerType);
        if (serviceName != null) {
            ImpactChainNode serviceNode = createTypeNode(serviceName, null, entryPoint.module());
            nodes.add(serviceNode);
            relations.add(new ImpactChainRelation(controllerNode.id(), serviceNode.id(), RelationType.DEPENDS_ON, "HTTP_CALL"));

            // Simulate repository layer
            String repositoryName = deriveRepositoryName(serviceName);
            if (repositoryName != null) {
                ImpactChainNode repoNode = createTypeNode(repositoryName, null, entryPoint.module());
                nodes.add(repoNode);
                relations.add(new ImpactChainRelation(serviceNode.id(), repoNode.id(), RelationType.DEPENDS_ON, "SERVICE_CALL"));
            }
        }
    }

    private void buildRpcImpactChain(EntryPoint entryPoint, List<ImpactChainNode> nodes, List<ImpactChainRelation> relations) {
        // RPC entry points follow: RPC Service -> Business Service -> Repository
        String serviceType = entryPoint.className();

        ImpactChainNode serviceNode = createTypeNode(serviceType, entryPoint.filePath(), entryPoint.module());
        nodes.add(serviceNode);
        relations.add(new ImpactChainRelation(entryPoint.id(), serviceNode.id(), RelationType.DECLARES, null));

        // RPC services often call other services or access data
        String internalServiceName = deriveInternalServiceName(serviceType);
        if (internalServiceName != null) {
            ImpactChainNode internalServiceNode = createTypeNode(internalServiceName, null, entryPoint.module());
            nodes.add(internalServiceNode);
            relations.add(new ImpactChainRelation(serviceNode.id(), internalServiceNode.id(), RelationType.INVOKES, "RPC_DELEGATE"));
        }
    }

    private void buildMqImpactChain(EntryPoint entryPoint, List<ImpactChainNode> nodes, List<ImpactChainRelation> relations) {
        // MQ consumers follow: Listener -> Handler/Service -> Repository/External
        String listenerType = entryPoint.className();

        ImpactChainNode listenerNode = createTypeNode(listenerType, entryPoint.filePath(), entryPoint.module());
        nodes.add(listenerNode);
        relations.add(new ImpactChainRelation(entryPoint.id(), listenerNode.id(), RelationType.DECLARES, null));

        // MQ listeners typically delegate to handlers
        String handlerName = deriveHandlerName(listenerType);
        if (handlerName != null) {
            ImpactChainNode handlerNode = createTypeNode(handlerName, null, entryPoint.module());
            nodes.add(handlerNode);
            relations.add(new ImpactChainRelation(listenerNode.id(), handlerNode.id(), RelationType.INVOKES, "MQ_CONSUME"));
        }
    }

    private void buildScheduledImpactChain(EntryPoint entryPoint, List<ImpactChainNode> nodes, List<ImpactChainRelation> relations) {
        // Scheduled tasks follow: Scheduled Method -> Business Logic -> Repository/External API
        String taskType = entryPoint.className();

        ImpactChainNode taskNode = createTypeNode(taskType, entryPoint.filePath(), entryPoint.module());
        nodes.add(taskNode);
        relations.add(new ImpactChainRelation(entryPoint.id(), taskNode.id(), RelationType.DECLARES, null));

        // Scheduled tasks often call services
        String serviceName = deriveServiceName(taskType);
        if (serviceName != null) {
            ImpactChainNode serviceNode = createTypeNode(serviceName, null, entryPoint.module());
            nodes.add(serviceNode);
            relations.add(new ImpactChainRelation(taskNode.id(), serviceNode.id(), RelationType.INVOKES, "SCHEDULED_EXEC"));
        }
    }

    private void buildEventImpactChain(EntryPoint entryPoint, List<ImpactChainNode> nodes, List<ImpactChainRelation> relations) {
        // Event listeners follow: Event Listener -> Event Handler -> Multiple Services
        String listenerType = entryPoint.className();

        ImpactChainNode listenerNode = createTypeNode(listenerType, entryPoint.filePath(), entryPoint.module());
        nodes.add(listenerNode);
        relations.add(new ImpactChainRelation(entryPoint.id(), listenerNode.id(), RelationType.DECLARES, null));

        // Event listeners may publish other events or call services
        String handlerName = deriveHandlerName(listenerType);
        if (handlerName != null) {
            ImpactChainNode handlerNode = createTypeNode(handlerName, null, entryPoint.module());
            nodes.add(handlerNode);
            relations.add(new ImpactChainRelation(listenerNode.id(), handlerNode.id(), RelationType.TRIGGERS, "EVENT_HANDLE"));
        }
    }

    private ImpactChainNode createTypeNode(String fqn, String filePath, String module) {
        String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
        return new ImpactChainNode(
                "TYPE:" + fqn,
                NodeType.TYPE,
                simpleName,
                fqn,
                "CLASS",
                filePath,
                module,
                null,
                null,
                false,
                null
        );
    }

    private List<ImpactChainNode> deduplicateNodes(List<ImpactChainNode> nodes) {
        return nodes.stream()
                .collect(Collectors.toMap(
                        ImpactChainNode::id,
                        node -> node,
                        (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .toList();
    }

    private List<ImpactChainRelation> deduplicateRelations(List<ImpactChainRelation> relations) {
        return relations.stream()
                .collect(Collectors.toMap(
                        r -> r.fromId() + "->" + r.toId() + ":" + r.type(),
                        r -> r,
                        (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .toList();
    }

    // Helper methods for deriving related class names
    private String deriveServiceName(String controllerType) {
        return controllerType.replace("Controller", "Service")
                .replace("ControllerImpl", "Service");
    }

    private String deriveRepositoryName(String serviceName) {
        return serviceName.replace("Service", "Repository")
                .replace("Service", "Mapper");
    }

    private String deriveInternalServiceName(String rpcServiceType) {
        if (rpcServiceType.endsWith("Service")) {
            return rpcServiceType + "Impl";
        }
        return rpcServiceType + "Service";
    }

    private String deriveHandlerName(String listenerType) {
        return listenerType.replace("Listener", "Handler")
                .replace("Consumer", "Handler");
    }

    /**
     * Query impact chain for an entry point.
     */
    public List<Map<String, Object>> queryImpactChain(String entryPointId, int depth) {
        return repository.findImpactChain(entryPointId, depth);
    }

    /**
     * Get all entry points.
     */
    public List<Map<String, Object>> getAllEntryPoints() {
        return repository.findAllEntryPoints();
    }

    /**
     * Get entry points by type.
     */
    public List<Map<String, Object>> getEntryPointsByType(EntryPointType type) {
        return repository.findEntryPointsByType(type);
    }

    /**
     * Get impact graph for visualization.
     */
    public ImpactChainRepository.GraphData getImpactGraph(String entryPointId, int depth) {
        return repository.findImpactGraph(entryPointId, depth);
    }

    /**
     * Get full impact graph filtered by types.
     */
    public ImpactChainRepository.GraphData getFullImpactGraph(List<EntryPointType> types) {
        return repository.findFullImpactGraph(types);
    }

    public record AnalysisResult(
            int entryPointCount,
            int nodeCount,
            int relationCount
    ) {
    }

    public record ImpactChain(
            List<ImpactChainNode> nodes,
            List<ImpactChainRelation> relations
    ) {
    }
}
