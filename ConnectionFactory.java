package net.floodlightcontroller.lbx;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConnectionFactory {
	
	static String status="";
	
	public static Connection getConnection(){
		
		Connection conn = null;
		
		try{
			
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			
			String url = "jdbc:mysql://127.0.0.1/LBX";
			String usr = "root";
			String pwd = "root";
			
			conn = DriverManager.getConnection(url,usr,pwd);
			
			status = "Conexão aberta";
			
		}catch (SQLException e){
			status = e.getMessage();
		}catch (ClassNotFoundException e){
			status = e.getMessage();
		}catch (Exception e){
			e.getMessage();
		}
		
		return conn;
	}
	
	
	

}

