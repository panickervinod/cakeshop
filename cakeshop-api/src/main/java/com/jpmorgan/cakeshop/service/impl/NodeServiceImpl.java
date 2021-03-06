package com.jpmorgan.cakeshop.service.impl;

import static com.jpmorgan.cakeshop.service.impl.GethHttpServiceImpl.*;

import com.google.common.base.Joiner;
import com.jpmorgan.cakeshop.bean.GethConfigBean;
import com.jpmorgan.cakeshop.dao.PeerDAO;
import com.jpmorgan.cakeshop.error.APIException;
import com.jpmorgan.cakeshop.model.Node;
import com.jpmorgan.cakeshop.model.NodeConfig;
import com.jpmorgan.cakeshop.model.Peer;
import com.jpmorgan.cakeshop.service.GethHttpService;
import com.jpmorgan.cakeshop.service.GethRpcConstants;
import com.jpmorgan.cakeshop.service.NodeService;
import com.jpmorgan.cakeshop.service.QuorumService;
import com.jpmorgan.cakeshop.util.AbiUtils;
import com.jpmorgan.cakeshop.util.EEUtils;
import com.jpmorgan.cakeshop.util.EEUtils.IP;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

@Service
public class NodeServiceImpl implements NodeService, GethRpcConstants {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NodeServiceImpl.class);

    @Value("${config.path}")
    private String CONFIG_ROOT;

    @Autowired
    private GethHttpService gethService;

    @Autowired
    private GethConfigBean gethConfig;

    @Autowired
    private PeerDAO peerDAO;

    @Autowired
    private QuorumService quorumService;

    @Override
    public Node get() throws APIException {

        Node node = new Node();

        Map<String, Object> data = null;

        try {
            //check if node is available
            data = gethService.executeGethCall(ADMIN_NODE_INFO);

            node.setRpcUrl(gethConfig.getRpcUrl());
            node.setDataDirectory(gethConfig.getDataDirPath());

            node.setId((String) data.get("id"));
            node.setStatus(StringUtils.isEmpty((String) data.get("id")) ? NODE_NOT_RUNNING_STATUS : NODE_RUNNING_STATUS);
            node.setNodeName((String) data.get("name"));

            // populate enode and url/ip
            String nodeURI = (String) data.get("enode");
            if (StringUtils.isNotEmpty(nodeURI)) {
                try {
                    URI uri = new URI(nodeURI);
                    String host = uri.getHost();
                    // if host or IP aren't set, then populate with correct IP
                    if(StringUtils.isEmpty(host) || "[::]".equals(host) ||  "0.0.0.0".equalsIgnoreCase(host)){

                        try {
                            List<IP> ips = EEUtils.getAllIPs();
                            uri = new URI(uri.getScheme(), uri.getUserInfo(), ips.get(0).getAddr(), uri.getPort(), null, uri.getQuery(), null);
                            node.setNodeUrl(uri.toString());
                            node.setNodeIP(Joiner.on(",").join(ips));

                        } catch (APIException ex) {
                            LOG.error(ex.getMessage());
                            node.setNodeUrl(nodeURI);
                            node.setNodeIP(host);
                        }

                    } else {
                        node.setNodeUrl(nodeURI);
                    }
                } catch (URISyntaxException ex) {
                    LOG.error(ex.getMessage());
                    throw new APIException(ex.getMessage());
                }
            }

            // check if mining
            if (!quorumService.isQuorum()) {
                data = gethService.executeGethCall(ADMIN_MINER_MINING);
                Boolean mining = (Boolean) data.get(SIMPLE_RESULT);
                node.setMining(mining == null ? false : mining);

            } else {
                // TODO assuming the equivalent of mining = true (temp workaround for quorum)
                node.setMining(true);
            }

            // peer count
            data = gethService.executeGethCall(ADMIN_NET_PEER_COUNT);
            String peerCount = (String) data.get(SIMPLE_RESULT);
            node.setPeerCount(peerCount == null ? 0 : Integer.decode(peerCount));

            // get last block number
            data = gethService.executeGethCall(ADMIN_ETH_BLOCK_NUMBER);
            String blockNumber = (String) data.get(SIMPLE_RESULT);
            node.setLatestBlock(blockNumber == null ? 0 : Integer.decode(blockNumber));

            // get pending transactions
            data = gethService.executeGethCall(ADMIN_TXPOOL_STATUS);
            Integer pending = AbiUtils.hexToBigInteger((String) data.get("pending")).intValue();
            node.setPendingTxn(pending == null ? 0 : pending);

            try {
                node.setConfig(createNodeConfig());
            } catch (IOException e) {
                throw new APIException("Failed to read genesis block file", e);
            }

            node.setPeers(peers());

            if (quorumService.isQuorum()) {
                // add quorum info
                node.setQuorumInfo(quorumService.getQuorumInfo());
            }

        } catch (APIException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ResourceAccessException) {
                node.setStatus(NODE_NOT_RUNNING_STATUS);
                return node;
            }

            throw ex;

        } catch (NumberFormatException ex){
            LOG.error(ex.getMessage());
            throw new APIException(ex.getMessage());

        }

        return node;
    }

    private NodeConfig createNodeConfig() throws IOException {
        return new NodeConfig(gethConfig.getIdentity(), gethConfig.isMining(), gethConfig.getNetworkId(),
                gethConfig.getVerbosity(), gethConfig.getGenesisBlock(), gethConfig.getExtraParams());
    }

    @Override
    public NodeConfig update(
            Integer logLevel, Integer networkID, String identity, Boolean mining,
            String extraParams, String genesisBlock) throws APIException {

        boolean restart = false;
        boolean reset = false;

        if (networkID != null && networkID != gethConfig.getNetworkId()) {
            gethConfig.setNetworkId(networkID);
            restart = true;
        }

        if (StringUtils.isNotEmpty(identity) && !identity.contentEquals(gethConfig.getIdentity())) {
            gethConfig.setIdentity(identity);
            restart = true;
        }

        if (logLevel != null && logLevel != gethConfig.getVerbosity()) {
            gethConfig.setVerbosity(logLevel);
            if (!restart) {
                // make it live immediately
                gethService.executeGethCall(ADMIN_VERBOSITY, new Object[]{ logLevel });
            }
        }

        String currExtraParams = gethConfig.getExtraParams();
        if (extraParams != null && (currExtraParams == null || !extraParams.contentEquals(currExtraParams))) {
            gethConfig.setExtraParams(extraParams);
            restart = true;
        }

        try {
            if (StringUtils.isNotBlank(genesisBlock) && !genesisBlock.contentEquals(gethConfig.getGenesisBlock())) {
                gethConfig.setGenesisBlock(genesisBlock);
                reset = true;
            }
        } catch (IOException e) {
            throw new APIException("Failed to update genesis block", e);
        }

        if (!quorumService.isQuorum() && mining != null && mining != gethConfig.isMining()) {
            gethConfig.setMining(mining);

            if (!restart) {
                // make it live immediately
                if (mining == true) {
                    gethService.executeGethCall(ADMIN_MINER_START, "1");
                } else {
                    gethService.executeGethCall(ADMIN_MINER_STOP);
                }
            }
        }

        NodeConfig nodeInfo;
        try {
            gethConfig.save();
            nodeInfo = createNodeConfig();
        } catch (IOException e) {
            LOG.error("Error saving config", e);
            throw new APIException("Error saving config", e);
        }

        // TODO reset/restart in background?
        if (reset) {
            gethService.reset();
        } else if (restart) {
            restart();
        }

        return nodeInfo;
    }

    @Override
    public Boolean reset() {
        try {
            gethConfig.initFromVendorConfig();
        } catch (IOException e) {
            LOG.warn("Failed to reset config file", e);
            return false;
        }

        restart();
        return true;
    }

    private void restart() {
        gethService.stop();
        gethService.deletePid();
        gethService.start();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Peer> peers() throws APIException {
        Map<String, Object> data = gethService.executeGethCall(ADMIN_PEERS);

        if (data == null) {
            return null;
        }

        List<Peer> peerList = new ArrayList<>();
        List<Map<String, Object>> peers = (List<Map<String, Object>>) data.get("_result");
        if (peers != null) {
            for (Map<String, Object> peerMap : peers) {
                Peer peer = createPeer(peerMap);
                peerList.add(peer);
            }
        }

        return peerList;
    }

    @Override
    public boolean addPeer(String address) throws APIException {

        URI uri = null;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            throw new APIException("Bad peer address URI: " + address, e);
        }

        Map<String, Object> res = gethService.executeGethCall(ADMIN_PEERS_ADD, address);
        if (res == null) {
            return false;
        }

        boolean added = (boolean) res.get(SIMPLE_RESULT);

        if (added) {
            Peer peer = new Peer();
            peer.setId(uri.getUserInfo());
            peer.setNodeIP(uri.getHost());
            peer.setNodeUrl(address);
            peerDAO.save(peer);
            // TODO if db is not enabled, save peers somewhere else? props file?
        }

        return added;
    }

    @SuppressWarnings("unchecked")
    private Peer createPeer(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        Peer peer = new Peer();
        peer.setStatus("running");
        peer.setId((String) data.get("id"));
        peer.setNodeName((String) data.get("name"));

        try {
            String remoteAddress = (String) ((Map<String, Object>) data.get("network")).get("remoteAddress");
            URI uri = new URI("enode://" + peer.getId() + "@" + remoteAddress);
            peer.setNodeUrl(uri.toString());
            peer.setNodeIP(uri.getHost());

        } catch (URISyntaxException ex) {
            LOG.error("error parsing Peer Address ", ex.getMessage());
            peer.setNodeUrl("");
        }

        return peer;
    }

}
