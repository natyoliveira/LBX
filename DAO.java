package net.floodlightcontroller.lbx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;


public class DAO {
	
	private  Connection conn;
	static String status = "";
	
	public DAO(){
		
	}
	
	public String addPath(int swSrcID, int swDstID, String srcMAC, String dstMAC, int FlowID, String Path){
		
		conn = ConnectionFactory.getConnection();
		
		String sql = "insert into lbx(SwSrcID,SwDstID,SrcMAC,DstMAC,FlowID,Path) values (?,?,?,?,?,?)";
		
		try{
			PreparedStatement stmt = conn.prepareStatement(sql);
			stmt.setInt(1,swSrcID);
			stmt.setInt(2,swDstID);
			stmt.setString(3,srcMAC);
			stmt.setString(4,dstMAC);
			stmt.setInt(5,FlowID);
			stmt.setString(6,Path);
			stmt.execute();
			stmt.close();
			status = "Inserido no Banco de Dados";
			
		}catch(SQLException e){
			status = e.getMessage();
		}
		
		return status;
	}

	public LbxComponents list(int SwSrcID, int SwDstID, int tos){

		ResultSet rs = null;
		
		conn = ConnectionFactory.getConnection();
		LbxComponents lbxComponents = new LbxComponents();
		
		String sql = "select * from lbx where SwSrcID = '"+SwSrcID+"' AND SwDstID = '"+SwDstID+"' AND FlowID = '"+tos+"'  ORDER BY SwSrcID DESC";
		
		try{
			PreparedStatement stmt = conn.prepareStatement(sql);
			rs = stmt.executeQuery();
			
			if(!rs.isBeforeFirst()){
				lbxComponents.setPath(null);
				}
			
			while(rs.next()){
				lbxComponents.setVlanID(rs.getInt("FlowID"));
				lbxComponents.setPath(rs.getString("Path"));
			}
			   
			
		}catch(SQLException e){
			e.printStackTrace();
		}
		
		return lbxComponents;
		
	}
	
}

class LbxComponents{
	public int vlanID;
	public String path;
	
	
	public void setVlanID(int vlanID){
		this.vlanID = vlanID;
	}
	
	public void setPath(String path){
		this.path = path;
	}
	
	public int getVlanID(){
		return vlanID;
	}
	
	public String getPath(){
		return path;
	}
	
}

