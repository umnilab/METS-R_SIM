package evacSim.citycontext;

import java.util.Comparator;

class LaneComparator implements Comparator<Lane>
{
   // parameter are of type Object, so we have to downcast it to vehicle objects
   public int compare(Lane l1, Lane l2)
   {
      int LaneID1, LaneID2;
      
      LaneID1 = Math.abs(l1.getLaneid());
      LaneID2 = Math.abs(l2.getLaneid());
 
      if (LaneID1 < LaneID2)
         return -1;
      else if (LaneID1 > LaneID2)
         return 1;
      else
         return 0;
   }


   /*@Override
   public int compare(Object arg0, Object arg1)
   {
      // TODO Auto-generated method stub
      return 0;
   }*/
}
