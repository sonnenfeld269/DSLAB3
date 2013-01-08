/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package auctionmanagement;

/**
 *
 * @author sanker
 */
public class TentativeBidEntry {
    int auctionID=0;
    String groupBidUser=null;
    String FirstConfirmUser=null;
    String SecondConfirmUser=null;
    double amount;
    TentativeBidEntry(int auctionID,
            String groupBidUser,
            double amount){
        this.amount=amount;
        this.auctionID=auctionID;
        this.groupBidUser=groupBidUser;        
    }
    
    public int getAuctionID()
    {
        return this.auctionID;
    }
    
    public String getFirstConfirmUser()
    {
        return this.FirstConfirmUser;
    }
    
    public String getSecondConfirmUser()
    {
        return this.SecondConfirmUser;
    }
    
    public String getgroupBidUser()
    {
        return this.groupBidUser;
    }
     
    public double getBid()
    {
        return this.amount;
    }
    
    public void setSecondConfirmUser(String user)
    {
        this.SecondConfirmUser=user;
    }
    
    public void setFirstConfirmUser(String user)
    {
        this.FirstConfirmUser=user;
    }
    
   
}