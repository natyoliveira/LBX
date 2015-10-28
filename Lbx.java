package net.floodlightcontroller.lbx;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.OFMessageDamper;

public class Lbx implements IFloodlightModule, IOFMessageListener{

	protected IFloodlightProviderService floodlightProvider;
	protected static Logger logger;
	protected IDeviceService deviceProvider;
	protected ILinkDiscoveryService linkDiscoveryProvider;
    protected ICounterStoreService counterStore;
    protected OFMessageDamper messageDamper;
    
    public static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
    public static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
    protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // TODO: find sweet spot
    protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms
    public static final int FORWARDING_APP_ID = 2; // TODO: This must be managed
    // by a global APP_ID class
    
    
	@Override
	public String getName() {
		return Lbx.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		
		switch (msg.getType()){
		case PACKET_IN:
			
			Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
			OFPacketIn pi = (OFPacketIn)msg;	
			
			OFMatch match = new OFMatch();
			match.loadFromPacket(pi.getPacketData(), pi.getInPort());
			
			long srcLong = eth.toLong(match.getDataLayerSource());
			long dstLong = eth.toLong(match.getDataLayerDestination());
			
			if(eth.isBroadcast() || eth.isMulticast()){
				doFlood(sw,pi,cntx,logger);
			}
			else {

			 String srcMAC = HexString.toHexString(srcLong);
			 String dstMAC = HexString.toHexString(dstLong);
			 String chosenPath = "";
			
			 int SwSrcID = 3;
			 int SwDstID = 2;
				
			 chosenPath = calculateLBX(SwSrcID,SwDstID,srcMAC,dstMAC,match);
			 boolean requestFlowRemovedNotifn = false;
			 doForwardFlow(sw, pi, cntx, requestFlowRemovedNotifn, logger, srcLong, dstLong, chosenPath);	
			
			}
			break;
		default:
			break;
		}
		
		return Command.CONTINUE;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IDeviceService.class);
		l.add(ILinkDiscoveryService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		deviceProvider = context.getServiceImpl(IDeviceService.class);
		linkDiscoveryProvider = context.getServiceImpl(ILinkDiscoveryService.class);
		logger = LoggerFactory.getLogger(Lbx.class);
		messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,
                EnumSet.of(OFType.FLOW_MOD),
                OFMESSAGE_DAMPER_TIMEOUT);

	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);

	}

    protected void doFlood(IOFSwitch sw, OFPacketIn pi,FloodlightContext cntx, Logger log){
    	
    	// Set Action to flood
        OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
        List<OFAction> actions = new ArrayList<OFAction>();
        
        if (sw.hasAttribute(IOFSwitch.PROP_SUPPORTS_OFPP_FLOOD)) {
            actions.add(new OFActionOutput(OFPort.OFPP_FLOOD.getValue(),
                                           (short)0xFFFF));
        } else {
            actions.add(new OFActionOutput(OFPort.OFPP_ALL.getValue(),
                                           (short)0xFFFF));
        }
        
        po.setActions(actions);
        po.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);

        // set buffer-id, in-port and packet-data based on packet-in
        short poLength = (short)(po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);
        
        po.setBufferId(pi.getBufferId());
        po.setInPort(pi.getInPort());
        
        if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            byte[] packetData = pi.getPacketData();
            poLength += packetData.length;
            po.setPacketData(packetData);
        }
        po.setLength(poLength);
        
        try {
            messageDamper.write(sw, po, cntx);
        } catch (IOException e) {
            log.info("Failure writing PacketOut switch={} packet-in={} packet-out={}" +sw+pi+po);
        }

        return;
    }

	public String calculateLBX(int SwSrcID, int SwDstID, String srcMAC, String dstMAC, OFMatch match){
		
	  int tos = match.getNetworkTypeOfService();
     
	  //Buscar PATH no MySql
	  DAO dao = new DAO();
	  LbxComponents lbx = dao.list(SwSrcID, SwDstID, tos);
	  
	  String Path = lbx.getPath();
	  logger.info("Path=" + Path);
	  int TOS = lbx.getVlanID();
	  logger.info("VlanID" + TOS);
	 
	  String npath = "";
	  

		//Se PATH for diferente de nulo, algoritmo LBX é acionado
		if (Path != null){			
			logger.info("Caminho:"+Path);
			logger.info("LBX - Novo menor caminho sendo calculado.");
			String[] result = Path.split(",");
			int[] path = new int [5];
			int m,n =0;
			
			for(m=0; m<result.length; m++){
				path[n] = Integer.parseInt(result[m]);
				n++;
			}
			
			Map<Long,IOFSwitch> switches = floodlightProvider.getAllSwitchMap();
			Map<Link,LinkInfo> links = linkDiscoveryProvider.getLinks();
			Vertex[] vertices = new Vertex[3];

			int i=0,j,k;
			for (IOFSwitch sw : switches.values()) {
				 vertices[i++] = new Vertex(sw.getId());
			}

			for(i=0;i<3;i++){
			  j=0;
			  Edge[] edge = new Edge[1];
			  links = linkDiscoveryProvider.getLinks();
				
				for (Link link : links.keySet()) {
					 if(link.getSrc() == vertices[i].switchDpid){
					      for(k=0;k<3;k++){
							  if(vertices[k].switchDpid == link.getDst())
							  break;
						  }
	 
						  if(j==0){
							edge[j++]=new Edge(vertices[k]);
						  }
						  else{
							edge = Arrays.copyOf(edge, edge.length + 1);
							edge[j++]=new Edge(vertices[k]);
						  }						  
						}
					}
				  vertices[i].adjacencies=edge;
				}

			 for (k=0;k<3;k++) {
			      
				 if (vertices[k].switchDpid == SwSrcID){
			      //logger.info("\nSwitch "+vertices[k].switchDpid+"'s News Adjacency is = ");
				 }			
				 
				 if (vertices[k].switchDpid == SwSrcID){
					 Edge[] newEdge = new Edge[vertices[k].adjacencies.length-1];
					 int position = 0;
					 
					 for (Edge e : vertices[k].adjacencies){
						   if (e.target.switchDpid != path[1]){
							   newEdge[position] = new Edge(e.target);
							  // logger.info("Switch "+newEdge[position].target.switchDpid+",");
							   position++;
						   }		         
					 }
					 vertices[k].adjacencies=newEdge;
				 }
			 }		
	          
		   
			 for(k=0;k<3;k++){
				   
				   if (vertices[k].switchDpid == SwSrcID){
				     //logger.info("\nFrom Switch "+vertices[k].switchDpid+"\n==================================");
				   }
				   
				   for(i=0;i<3;i++){
						  vertices[i].minDistance = Double.POSITIVE_INFINITY;
						  vertices[i].previous=null;
				      }
				   
				   
				   if (vertices[k].switchDpid == SwSrcID){ 
	  			     computePaths(vertices[k]);
				   }
				   String NewPath = "";
				   for (Vertex v : vertices){
					   if(v.switchDpid == SwDstID && v.minDistance != Double.POSITIVE_INFINITY){
						  // logger.info("\nDistance to " + v + ": " + v.minDistance+",");
						
						   ArrayList<Vertex> newpath = getShortestPathTo(v);
						   logger.info("Novo Caminho Escolhido: " + newpath);
						   
						   NewPath = newpath.toString();
						   
						   npath = RemoveCaracter(NewPath);

						   DAO dao2 = new DAO();
						   String status = dao2.addPath(SwSrcID, SwDstID, srcMAC, dstMAC, tos, npath);
						   logger.info(status+"\n");
					   }	
					}
			}
	    //Caso contrário, algoritmo Dijkstra é acionado
		} else{
			
			//TODO: Calcular Dijkstra
			logger.info("Nenhum caminho já calculado.");
			logger.info("Dijkstra - Menor caminho sendo calculado.");
			Map<Long,IOFSwitch> switches = floodlightProvider.getAllSwitchMap();
			Map<Link,LinkInfo> links = linkDiscoveryProvider.getLinks();
			Vertex[] vertices = new Vertex[3];

			int i=0,j,k;
			for (IOFSwitch sw : switches.values()) {
				 vertices[i++] = new Vertex(sw.getId());
			}

			for(i=0;i<3;i++){
			  j=0;
			  Edge[] edge = new Edge[1];
			  links = linkDiscoveryProvider.getLinks();
				
				for (Link link : links.keySet()) {
					 if(link.getSrc() == vertices[i].switchDpid){
					      for(k=0;k<3;k++){
							  if(vertices[k].switchDpid == link.getDst())
							  break;
						  }
	 
						  if(j==0){
							edge[j++]=new Edge(vertices[k]);
						  }
						  else{
							edge = Arrays.copyOf(edge, edge.length + 1);
							edge[j++]=new Edge(vertices[k]);
						  }
						  
						}
					}
				  vertices[i].adjacencies=edge;
				}

			 for (k=0;k<3;k++) {
				 if (vertices[k].switchDpid == SwSrcID){
				      //logger.info("\nSwitch "+vertices[k].switchDpid+"'s Adjacency is = ");
				     
				      for (Edge e : vertices[k].adjacencies){
					        //logger.info("Switch "+e.target.switchDpid+",");
					        
					    }
				 }
			   }
					
			   for(k=0;k<3;k++){
				   if (vertices[k].switchDpid == SwSrcID){
					     //logger.info("\nFrom Switch "+vertices[k].switchDpid+"\n==================================");
				   }
				   
				   
				   for(i=0;i<3;i++){
					  vertices[i].minDistance = Double.POSITIVE_INFINITY;
					  vertices[i].previous=null;
				   }

				   if (vertices[k].switchDpid == SwSrcID){ 
		  			     computePaths(vertices[k]);
				   }
	 
				   String NewPath = "";
				   for (Vertex v : vertices){
					   if(v.switchDpid == SwDstID && v.minDistance != Double.POSITIVE_INFINITY){
						   //logger.info("\nDistance to " + v + ": " + v.minDistance+",");
						
						   ArrayList<Vertex> newpath = getShortestPathTo(v);
						   logger.info("Menor caminho: " + newpath);
						   
						   NewPath = newpath.toString();
						   npath = RemoveCaracter(NewPath);
						   
						   DAO dao2 = new DAO();
						   String status = dao2.addPath(SwSrcID, SwDstID, srcMAC, dstMAC, tos, npath);
						   logger.info(status+"\n");
						
					}
			  }
		  }
		}
		 return npath;
	}
	
	public static void computePaths(Vertex source){
 
		source.minDistance = 0.;
	    PriorityQueue<Vertex> vertexQueue = new PriorityQueue<Vertex>();
		vertexQueue.add(source);

		  while (!vertexQueue.isEmpty()) {
		     Vertex u = vertexQueue.poll();

	        // Visit each edge exiting u
	        for (Edge e : u.adjacencies){
	            Vertex v = e.target;
	            double weight = e.weight;
	            double distanceThroughU = u.minDistance + weight;
	            
				if (distanceThroughU < v.minDistance) {
				    vertexQueue.remove(v);

				    v.minDistance = distanceThroughU ;
				    
				    v.previous = u;
				    vertexQueue.add(v);
				}
	        }
	      }
	    }
	
	public static ArrayList<Vertex> getShortestPathTo(Vertex target){
		  ArrayList<Vertex> path = new ArrayList<Vertex>();
	      for (Vertex vertex = target; vertex != null; vertex = vertex.previous)
	          path.add(vertex);

	      Collections.reverse(path);
	      return path;
	}
	
	public String RemoveCaracter(String s){
		String[] caracter = {"\\.", "[", "]", "\\(", "\\)", "ª", "\\|", "\\\\", " "};
		 
	    for (int i = 0; i < caracter.length; i++) {
	        s = s.replace(caracter[i],"");
	    } 
	    
	    return s;
	}
  
	public void doForwardFlow(IOFSwitch sw, OFPacketIn pi, 
    		FloodlightContext cntx, boolean requestFlowRemovedNotifn, Logger log, long srcLong, long dstLong, String chosenPath) {
		
		// Creating a match
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        
        //logger.info("tos doForwardFlow = {}", match.getNetworkTypeOfService());
        
        // Gets the destination device
        IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
        //log.info("Destination Device:" +dstDevice);
        
        if (dstDevice != null) {
            // Gets the source device
            IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
            //log.info("Source Device:" +srcDevice);
            if (srcDevice == null) {
                return;
            }
       }
        
        List<NodePortTuple> path = new LinkedList<NodePortTuple>();

       // chosenPath 2,3 or 3,2
        if (chosenPath.equals("2,3") || chosenPath.equals("3,2") ){
        	// Create path variables and node port tuples
            NodePortTuple npt1 = new NodePortTuple(3,3); //switch 3, port 3
            NodePortTuple npt2 = new NodePortTuple(3,1); //switch 3, port 1
            NodePortTuple npt3 = new NodePortTuple(2,1); //switch 2, port 1
            NodePortTuple npt4 = new NodePortTuple(2,3); //switch 2, port 3
        	   
            //Add NodePortTuples in the order shown above if h5 is trying to ping h6 or h6 ping h5
            //The route in each direction is different
            if(sw.getId() == 2) {
               path.add(npt4);
               path.add(npt3);
               path.add(npt2);
               path.add(npt1);
            }
            else if(sw.getId() == 3) {
                path.add(npt1);
                path.add(npt2);
                path.add(npt3);
                path.add(npt4);
           }

        // chosenPath 2,4,3 or 3,4,2
        }else if (chosenPath.equals("2,3,4") || chosenPath.equals("3,4,2") ){
        	// Create path variables and node port tuples
            NodePortTuple npt1 = new NodePortTuple(3,3); //switch 3, port 3
            NodePortTuple npt2 = new NodePortTuple(3,2); //switch 3, port 2
            NodePortTuple npt3 = new NodePortTuple(4,1); //switch 4, port 1
            NodePortTuple npt4 = new NodePortTuple(4,2); //switch 4, port 2
            NodePortTuple npt5 = new NodePortTuple(2,2); //switch 2, port 2
            NodePortTuple npt6 = new NodePortTuple(2,3); //switch 2, port 3
        
            //Add NodePortTuples in the order shown above if h5 is trying to ping h6 or h6 ping h5
            //The route in each direction is different
            if(sw.getId() == 2) {
            	path.add(npt6);
            	path.add(npt5);
            	path.add(npt4);
                path.add(npt3);
                path.add(npt2);
                path.add(npt1);
            }
            else if(sw.getId() == 3) {
                path.add(npt1);
                path.add(npt2);
                path.add(npt3);
                path.add(npt4);
                path.add(npt5);
                path.add(npt6);
           }

        }
       
      //Create the route
      Route route = new Route(new RouteId(srcLong, dstLong), path);
      //log.info("Route in doForwardFlow:\n" + route);


      // If any route exists, it needs to be installed
      if (route != null) {
          // Creates a cookie for the route
          long cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

          // Creates Wildcard Hints
          Integer wildcard_hints = null;
          wildcard_hints = ((Integer) sw
                        .getAttribute(IOFSwitch.PROP_FASTWILDCARDS))
                        .intValue()
                        & ~OFMatch.OFPFW_IN_PORT
                        & ~OFMatch.OFPFW_DL_VLAN
                        & ~OFMatch.OFPFW_DL_SRC
                        & ~OFMatch.OFPFW_DL_DST
                        & ~OFMatch.OFPFW_NW_SRC_MASK
                        & ~OFMatch.OFPFW_NW_DST_MASK;
                // Here the route is installed in the network using pushRoute method from ForwardingBase class
           pushRoute(route, match, wildcard_hints, pi, sw.getId(), cookie,cntx, requestFlowRemovedNotifn, false,OFFlowMod.OFPFC_ADD, log);
      }
      
   }
	
	public boolean pushRoute(Route route, OFMatch match,Integer wildcard_hints,OFPacketIn pi,long pinSwitch,long cookie,
            FloodlightContext cntx,boolean reqeustFlowRemovedNotifn,boolean doFlush,short flowModCommand, Logger log) {

		boolean srcSwitchIncluded = false;
		OFFlowMod fm = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
		
		OFActionOutput action = new OFActionOutput();
		action.setMaxLength((short)0xffff);
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(action);

		fm.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
		.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
		.setBufferId(OFPacketOut.BUFFER_ID_NONE)
		.setCookie(cookie)
		.setCommand(flowModCommand)
		.setMatch(match)
		.setActions(actions)
		.setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionOutput.MINIMUM_LENGTH);

		List<NodePortTuple> switchPortList = route.getPath();
		
		for (int indx = switchPortList.size()-1; indx > 0; indx -= 2) {
			// indx and indx-1 will always have the same switch DPID.
			
			long switchDPID = switchPortList.get(indx).getNodeId();

			IOFSwitch sw = floodlightProvider.getSwitch(switchDPID);

			
			if (sw == null) {
				log.info("Unable to push route, switch at DPID {} " +
				"not available"+ switchDPID);

				return srcSwitchIncluded;
			}

			// set the match.
			fm.setMatch(wildcard(match, sw, wildcard_hints));

			// set buffer id if it is the source switch
			if (1 == indx) {
		    // Set the flag to request flow-mod removal notifications only for the
			// source switch. The removal message is used to maintain the flow
			// cache. Don't set the flag for ARP messages - TODO generalize check
				if ((reqeustFlowRemovedNotifn)
						&& (match.getDataLayerType() != Ethernet.TYPE_ARP)) {
					/**with new flow cache design, we don't need the flow removal message from switch anymore
   		            fm.setFlags(OFFlowMod.OFPFF_SEND_FLOW_REM);*/
					match.setWildcards(fm.getMatch().getWildcards());
				}
			}
			short outPort = switchPortList.get(indx).getPortId();
			short inPort = switchPortList.get(indx-1).getPortId();

			// set input and output ports on the switch
			fm.getMatch().setInputPort(inPort);
			((OFActionOutput)fm.getActions().get(0)).setPort(outPort);
			
			try {
				//counterStore.updatePktOutFMCounterStoreLocal(sw, fm);
			
				log.info("Pushing Route flowmod routeIndx{}" +"sw{} inPort{} outPort{}", new Object[] {
						indx,sw,fm.getMatch().getInputPort(),outPort});
				
				messageDamper.write(sw, fm, cntx);
				
				if (doFlush) {
					sw.flush();
					counterStore.updateFlush();
				}

				// Push the packet out the source switch
				if (sw.getId() == pinSwitch) {
			      // TODO: Instead of doing a packetOut here we could also
                  // send a flowMod with bufferId set....
			      pushPacket(sw, pi, false, outPort, cntx,log);
                  srcSwitchIncluded = true;
				}
			} catch (IOException e) {
				log.info("Failure writing flow mod", e);
			}

			try {
				fm = fm.clone();
			} catch (CloneNotSupportedException e) {
				log.info("Failure cloning flow mod", e);
			}
		}
		return srcSwitchIncluded;
}
	
	protected void pushPacket(IOFSwitch sw, OFPacketIn pi, boolean useBufferId, short outport, FloodlightContext cntx, Logger log) {

		if (pi == null) {
			return;
		}

		// The assumption here is (sw) is the switch that generated the
		// packet-in. If the input port is the same as output port, then
		// the packet-out should be ignored.
		if (pi.getInPort() == outport) {
			log.info("Attempting to do packet-out to the same " +
					"interface as packet-in. Dropping packet. " +
					" SrcSwitch={}, pi={}",
					new Object[]{sw, pi});
				return;
		}

		log.info("PacketOut srcSwitch={} pi={}",new Object[] {sw, pi});

		OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory()
                                 .getMessage(OFType.PACKET_OUT);

		// set actions
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(new OFActionOutput(outport, (short) 0xffff));

		po.setActions(actions)
		.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
		short poLength = (short) (po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);

		if (useBufferId) {
			po.setBufferId(pi.getBufferId());
		} else {
			po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
		}

		if (po.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
			byte[] packetData = pi.getPacketData();
			poLength += packetData.length;
			po.setPacketData(packetData);
		}

		po.setInPort(pi.getInPort());
		po.setLength(poLength);

		try {
			//counterStore.updatePktOutFMCounterStoreLocal(sw, po);
			messageDamper.write(sw, po, cntx);
		} catch (IOException e) {
			log.info("Failure writing packet out", e);

		}
	}

    protected OFMatch wildcard(OFMatch match, IOFSwitch sw,
             Integer wildcard_hints) {
		 if (wildcard_hints != null) {
			 return match.clone().setWildcards(wildcard_hints.intValue());
         }
		 return match.clone();
}

    
    
	class Vertex implements Comparable<Vertex>{
	     public long switchDpid;
	     public Edge[] adjacencies;
	     public double minDistance = Double.POSITIVE_INFINITY;
	     public Vertex previous;
	     public Vertex(long switchDpid) { this.switchDpid = switchDpid; }
	     public String toString() { return ""+switchDpid; }
	     public int compareTo(Vertex other)
	     {
	        return Double.compare(minDistance, other.minDistance);
	     }
	    }

	class Edge{
	     public final Vertex target;
	     public final double weight;
	     public Edge(Vertex target, double weight) {
	        this.target = target;
	        this.weight = weight;
	     }
	     public Edge(Vertex target) {
	        this(target, 1.0);
	     }
	   }

	
	
	
	/*long srcLong = Ethernet.toLong(match.getDataLayerSource());
	long dstLong = Ethernet.toLong(match.getDataLayerDestination());
	
		
	String srcMAC = HexString.toHexString(srcLong);
	String dstMAC = HexString.toHexString(dstLong);
	
	String srcMAC = HexString.toHexString(srcLong);
	String dstMAC = HexString.toHexString(dstLong);
	
	int srcInt = match.getNetworkSource();
	int dstInt = match.getNetworkDestination();
	
	String ipSrc =  IPv4.fromIPv4Address(srcInt);
	String ipDst = IPv4.fromIPv4Address(dstInt);

	String macOrigem = "00:00:00:00:00:00:00:05";
	String macDestino = "00:00:00:00:00:00:00:06";
	
	String src = "10.0.0.5";
	String dst = "10.0.0.6";
	
	//logger.info("Source: "+srcMAC);
	//logger.info("Destination: "+dstMAC);
	//logger.info("PI Array: "+Arrays.asList(match)+"\n");
	
	if (ipSrc.equals(src) && ipDst.equals(dst)){
	
		logger.info("Entrei!");
		boolean requestFlowRemovedNotifn = false;
	
		doForwardFlow(sw, pi, cntx, requestFlowRemovedNotifn, logger, srcLong, dstLong);
		
	}*/
	
	
	
	
	/*if (ipSrc.equals(src) && ipDst.equals(dst)){
		int SwSrcID = 4;
		int SwDstID = 2;
		int vlanID = 1;
		
		logger.info("Source: "+srcMAC);
		logger.info("Destination: "+dstMAC);
		logger.info("PI Array: "+Arrays.asList(match)+"\n");
		
		
		//logger.info("PI Array: "+Arrays.asList(match)+"\n");
		
		//String srcMAC = "00:00:00:00:00:05";
		//String dstMAC = "00:00:00:00:00:06";
		
		//Forwarding f = new Forwarding();
		//boolean requestFlowRemovedNotifn = false;
	
		//f.doForwardFlow(sw, pi, cntx, requestFlowRemovedNotifn, logger, srcLong, dstLong);
		
		//calculateLBX(SwSrcID,SwDstID,srcMAC,dstMAC,vlanID);
		
	}*/
	
	
	
	
	/**
	 * CalculateLBX() com File bw
	*/
	/*File file = new File("/home/floodlight/Desktop/lbx3.txt"); 
	 
	if (!file.exists()){
	  try {
		  file.createNewFile();
		  FileWriter fw = new FileWriter(file.getAbsoluteFile());
		  BufferedWriter bw = new BufferedWriter(fw);
			  
		calculateLBX(bw);
			  
	   }catch (IOException e) {
		// TODO Auto-generated catch block
		 e.printStackTrace();
	}
   }*/
	
	/*logger.info("Aqui estou!");
	logger.info("SwID "+sw.getId());
	logger.info("Source: "+srcMAC);
	logger.info("Destination: "+dstMAC);
	logger.info("Source IP: "+ipSrc);
    logger.info("Dest IP: "+ipDst);
	logger.info("PI Array: "+Arrays.asList(match)+"\n");*/
	
}

