package scalanlp.stats.sampling;

/*
 Copyright 2009 David Hall, Daniel Ramage
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at 
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License. 
*/


import scalala.tensor.counters._;
import scalala.tensor.counters.Counters._;
import util._;
import scalanlp.collection.mutable.ArrayMap;
import scala.collection.mutable._;

/**
* Models the CRP over the non-negative integers. 
*
* @param theta the prior probability of a new class
* @param alpha the amount of discounting to current draws.
*
* @author dlwh
*/
class PitmanYorProcess private (
                       private val drawn: DoubleCounter[Int],
                       unobservedIndex: Int,
                       val theta: Double,
                       val alpha:Double)(implicit rand: RandBasis)
                       extends DiscreteDistr[Int] with Process[Int] { outer =>
  def this(theta: Double, alpha: Double)(implicit rand: RandBasis=Rand) = this(DoubleCounter[Int](), 0, theta,alpha);
  def this(theta: Double) = this(theta,0.0);

  assert( (alpha < 0 && theta % alpha == 0.0) || (0 <= alpha && alpha <= 1.0 && theta > -alpha));
  drawn(unobservedIndex) += theta;
  
  override def draw() = getWithCounter(drawn);

  /** The number of currently observed classes */
  def numClasses = drawn.size - 1;
  
  val nextClass = unobservedIndex;

  private def getWithCounter(cn : DoubleCounter[Int]) = {
    Multinomial(cn)(rand).draw
  }
  
  /** Returns the probability of a class if it's been observed, 0 otherwise. */
  def probabilityOf(e: Int) = {
    if(e >= 0) {
      drawn(e) / drawn.total;
    } else {
      0.0;
    }
  }

  /** Probability of the next class being drawn. */
  def probabilityOfUnobserved() = drawn(unobservedIndex) / drawn.total;

  /** Add or subtract some number of observations. Useful for sampling.*/
  def observe(c: IntCounter[Int]):PitmanYorProcess = {
    val ret = DoubleCounter[Int]();
    
    for( (k,v) <- drawn) {
      if(k != unobservedIndex && v != 0.0) {
        ret(k) += v;
        ret(k) += alpha;
      }
    }
    
    for( (k,v) <- c) {
      ret(k) += v;
      if( math.abs(ret(k)) < math.abs(1 - alpha - 1E-4) || ret(k) < 0 && math.abs(ret(k)) == math.abs(alpha)) {
        ret(k) = 0;
      } else if (ret(k) < 0) {
        throw new IllegalArgumentException("Reducing class " + k +"  to less than 0 " + ret(k))
      }
    }
    
    val index0 = {
        if(!(c contains unobservedIndex)) unobservedIndex 
        else {
          val idx = ret.iterator.indexWhere( (x:(Int,Double)) => x._2 == 0.0);
          if(idx == -1) ret.size;
          else idx;
      }
    }
    
    val numKeys = ret.iterator.filter( (x:(Int,Double)) => x._2 > 0.0).toSeq.size;
    
    ret.transform{ (k,v) =>
      if( k == index0) {
        numKeys * alpha;
      } else if(v > 0)  {
        v - alpha;
      } else {
        v
      }
    }
    
    new PitmanYorProcess(ret,index0,theta,alpha)(rand)
  }
  
  override def observe(c: Int):PitmanYorProcess = observe(Counters.count(List(c)));

  /** Convenience method. Observe some classes */
  def observe(t: Int, ts : Int*):PitmanYorProcess = { observe(count(ts)).observe(t)}

  /** Convenience method. Unobserve some classes */
  def unobserve(t: Int*) = {
    val c = count(t);
    c.transform { (k,v) => v * -1};
    observe(c);
  }

  /** Rand for drawing c, taking into account the likelihood passed in. None
  indicates that you should consider the probability of a new class. */
  def withLikelihood(p : Option[Int]=>Double) = new Rand[Int] {
    def draw = {
      val c2 = DoubleCounter[Int]();
      for( (k,v) <- drawn) {
        c2(k) = (v * p(if(k == 0) None else Some(k-1)));
      }
      getWithCounter(c2);
    }
  }

  /** withLikelihood followed by get */
  def drawWithLikelihood(p: Option[Int]=>Double) = withLikelihood(p).get;

  /**
  * Returns a new process based on the old draws that maps draws from 
  * the process to draws from the base measure.
  * 
  * *Not reentrant!*
  *
  * @param r: The base measure generator
  */
  class Mapped[T](r: Rand[T]) extends DiscreteDistr[T] with Process[T] {
    val forward: HashMap[Int,T]  = new HashMap[Int,T] {
      override def default(k:Int) = {
        val d = r.get;
        backward(d) += k;
        getOrElseUpdate(k,d);
      }
    }
    
    val backward: HashMap[T,ArrayBuffer[Int]] = new HashMap[T,ArrayBuffer[Int]] {
      override def default(d:T) = {
        getOrElseUpdate(d,new ArrayBuffer[Int]);
      }
    }
    
    def draw() = {
      forward(outer.draw);
    } 
    
    def observe(x:T):PitmanYorProcess#Mapped[T] = observe(count(List(x)));
    def observe(x:T, xs:T*):PitmanYorProcess#Mapped[T] = observe(count(List(x)++xs));
    
    def observe(c: IntCounter[T]):PitmanYorProcess#Mapped[T] = {
      val classes = c.iterator.filter(_._2 != 0).flatMap { case (t,vD) =>
        val v = vD.toInt;
        var firstValidClass = 0;
        def nextValidClass = {
          while(drawn(firstValidClass) != 0) firstValidClass +=1;
          firstValidClass;
        }
        
        backward.get(t) match {
          case Some(buf) =>
            val chooser = rand.choose(buf);
	          if(v < 0) {
	            (0 until v.abs).iterator map ( _ => (chooser.get,-1))
	          } else {
	            (0 until v).iterator map ( _ => (chooser.get,1))
	          }
          case None =>
            val clss = firstValidClass;
            List((clss->v)).iterator
        }
      }
      
      val countedClasses = IntCounter[Int]();
      countedClasses ++= classes;
      
      val py = outer.observe(countedClasses);
      
      val myF = forward;
      new py.Mapped(r) {
        forward ++= myF.filter(x => (outer probabilityOf x._1) > 0);
        for( (clss,e) <- forward) {
          backward(e) += clss; 
        }
      }
    }
    
    
    

    /** Sum over all classes that may have your draw */
    def probabilityOf(t: T) = backward.get(t).map(_.map(outer.probabilityOf _).foldLeft(0.0)(_+_)).getOrElse(0.0);
    def probabilityOfUnobserved() = outer.probabilityOfUnobserved();
  }

  /** Returns a Mapped[T] */
  def withBaseMeasure[T](r: Rand[T])= new Mapped[T](r);
  
  /** Returns a Mapped[T], with probabilityOf taking into
  * account the probability of drawing an item again. 
  */
  def withBaseMeasure[T](r: DiscreteDistr[T]):DiscreteDistr[T] =  withBaseMeasure(r);
    
  /** Returns a Mapped[T], with probabilityOf taking into
  * account the probability of drawing an item again. 
  */
  def withBaseMeasure[T](r: DiscreteDistr[T], observer: (T,Int)=>Unit) = new Mapped[T](r) {
    override def probabilityOf(t: T) = {
      val fromDraws = backward(t).map(outer.probabilityOf _).foldLeft(0.0)(_+_);
      val pDraw = r.probabilityOf(t) 
        pDraw * outer.probabilityOfUnobserved + fromDraws;
    }
  }

  override def toString() = {
    val str = drawn.iterator.map(kv => (kv._1)+ " -> " + kv._2).mkString("draws = (", ", ", ")");
    "PY(" + theta + "," + alpha + ")" + "\n{newClass=" + drawn(unobservedIndex) + ", " + str + "}";
  }

}
