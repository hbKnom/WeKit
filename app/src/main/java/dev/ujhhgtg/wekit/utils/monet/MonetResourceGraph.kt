package dev.ujhhgtg.wekit.utils.monet

import java.io.Serializable

class MonetResourceGraph(
    nodes: List<MonetResourceNode>,
    private val xmlByOwner: Map<Int, List<MonetXmlElement>> = emptyMap(),
) : Serializable {
    private val byId = nodes.associateBy(MonetResourceNode::id)
    private val byKey = nodes.associateBy(MonetResourceNode::key)
    private val outgoingById: Map<Int, Set<Int>> = byId.mapValues { (id, node) ->
        HashSet<Int>().also { references ->
            node.values.forEach { it.value.collectReferences(references) }
            xmlByOwner[id].orEmpty().forEach { it.collectReferences(references) }
        }
    }
    private val incomingById: Map<Int, Set<Int>> = HashMap<Int, MutableSet<Int>>().also { incoming ->
        outgoingById.forEach { (sourceId, targets) ->
            targets.forEach { targetId ->
                incoming.getOrPut(targetId, ::linkedSetOf).add(sourceId)
            }
        }
    }

    init {
        require(byId.size == nodes.size) { "duplicate resource ID" }
        require(byKey.size == nodes.size) { "duplicate resource key" }
        require(xmlByOwner.keys.all(byId::containsKey)) { "XML owner is absent from resource table" }
    }

    fun node(id: Int): MonetResourceNode? = byId[id]
    fun node(key: MonetResourceKey): MonetResourceNode? = byKey[key]
    fun nodes(type: String): List<MonetResourceNode> = byId.values.filter { it.key.type == type }

    /** 全部宿主资源节点。合成资源借槽位、类型统计都要用（只按角色取子集不够）。 */
    fun allNodes(): Collection<MonetResourceNode> = byId.values
    fun xmlTrees(ownerId: Int): List<MonetXmlElement> = xmlByOwner[ownerId].orEmpty()

    fun withXmlTree(ownerId: Int, tree: MonetXmlElement): MonetResourceGraph =
        MonetResourceGraph(byId.values.toList(), xmlByOwner + (ownerId to xmlTrees(ownerId) + tree))

    fun outgoing(id: Int): Set<Int> = outgoingById[id].orEmpty()
    fun incoming(id: Int): Set<Int> = incomingById[id].orEmpty()
}

private fun MonetXmlElement.collectReferences(result: MutableSet<Int>) {
    attributes.forEach { it.value.collectReferences(result) }
    children.forEach { it.collectReferences(result) }
}

private fun MonetResourceValue.collectReferences(result: MutableSet<Int>) {
    when (this) {
        is MonetResourceValue.Reference -> result += resourceId
        is MonetResourceValue.Complex -> {
            if (parentId != 0) result += parentId
            items.forEach { it.value.collectReferences(result) }
        }
        is MonetResourceValue.File,
        is MonetResourceValue.Text,
        is MonetResourceValue.Literal,
        -> Unit
    }
}
