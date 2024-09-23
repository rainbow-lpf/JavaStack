package com.axon.java.stack.data.structures.linklist;

import cn.hutool.json.JSON;


/**
 * 1.求单链表中有效节点的个数
 * 2.查找单链表中倒数第k个节点
 * 3.单链表进行反转
 * 4.从尾到头打印单链表
 * 5.合并两个有序的单链表，合并之后链表依然有序
 */
public class SingleLinkListDemo {

    public static void main(String[] args) {

        SingleLinkList list = new SingleLinkList();
        HeroNode heroNode1 = new HeroNode(30, "林冲");
        HeroNode heroNode2 = new HeroNode(10, "鲁智深");

        HeroNode heroNode3 = new HeroNode(50, "无用");

        HeroNode heroNode4 = new HeroNode(60, "武松");

        HeroNode heroNode5= new HeroNode(20, "松江");


        list.addHeroNodeByOrder(heroNode1);
        list.addHeroNodeByOrder(heroNode2);
        list.addHeroNodeByOrder(heroNode3);
        list.addHeroNodeByOrder(heroNode4);
        list.addHeroNodeByOrder(heroNode5);

        System.out.println("遍历原始数据。。。。。。。");
        list.showList();

        //开始删除节点

        list.removeHeroNode(10);
        System.out.println("删除后的节点列表。。。。。。。");
        list.showList();

        list.updateHero(new HeroNode(50,"智多星"));

        System.out.println("修改后的节点列表");

        list.showList();

        System.out.println();

    }
}


class SingleLinkList {

    //这是头节点
    HeroNode heroHeadNode = new HeroNode(0, null);

    /**
     * 添加英雄节点
     *
     * @param currentNode
     */
    public void addHeroNode(HeroNode currentNode) {
        //获取一个临时节点
        HeroNode temp = heroHeadNode;
        while (temp.getNext() != null) {
            temp = temp.getNext();
        }
        // 为下一个节点赋值
        temp.setNext(currentNode);
    }

    /**
     * 按照顺序添加有英雄节点
     *
     * @param currentNode
     */
    public void addHeroNodeByOrder(HeroNode currentNode) {
        //获取一个临时节点
        HeroNode temp = heroHeadNode;
        boolean flag = false;
        while (true) {
            if (temp.getNext() == null) {
                break;
            } else if (temp.getNext().getNo() > currentNode.getNo()) {
                break;
            } else if (temp.getNext().getNo() == currentNode.getNo()) {
                flag = true;
            }
            temp = temp.getNext();
        }
        if (flag) {
            System.out.println("编号 " + currentNode.getNo() + " 已经存在，无法添加");
        } else {
            currentNode.setNext(temp.getNext());
            // 为下一个节点赋值
            temp.setNext(currentNode);
        }
    }


    /**
     *   移除系节点
     * @param no
     */
    public void removeHeroNode(int no) {

        if (no < 0) {
            System.out.println("编号异常， 无法删除");
            return;
        }
        HeroNode temp = heroHeadNode;

        boolean flag = false;

        while (true) {

            if (temp.getNext() == null) {
                break;
            }
            if (temp.getNext().getNo() == no) {
                flag = true;
                break;
            }
            temp = temp.getNext();
        }
        if (flag) {
            //将当前节点设置为
            temp.setNext(temp.getNext().getNext());
        } else {
            System.out.println("未找到对应的节点");
        }
    }

    /**
     *  更新英雄
     * @param heroNode
     */
    public  void updateHero(HeroNode heroNode) {
        HeroNode tempNode = heroHeadNode;
        if (tempNode.getNext() == null) {
            System.out.println("当前链表的节点为空，无法进行修改");
            return;
        }

        boolean flag = false;
        while (true) {
            if (tempNode.getNo() == heroNode.getNo()) {
                flag = true;
                break;
            } else if (tempNode.getNext() == null) {
                break;
            }
            tempNode = tempNode.getNext();
        }
        if (flag) {
            //修改名称
            tempNode.setName(heroNode.getName());
        } else {
            System.out.println("yichang");
        }
    }


    // 展示链表中的数据
    public void showList() {
        // 判断链表是否为空
        if (heroHeadNode.getNext() == null) {
            System.out.println("链表为空");
            return;
        }

        // 因为头节点不能动，因此需要一个临时变量来遍历
        HeroNode temp = heroHeadNode.getNext();
        while (temp != null) {
            // 输出节点的信息
            System.out.println(temp);
            temp = temp.getNext();
        }
    }

}


class HeroNode {

    public HeroNode getNext() {
        return next;
    }

    public void setNext(HeroNode next) {
        this.next = next;
    }

    public int getNo() {
        return no;
    }

    public void setNo(int no) {
        this.no = no;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private int no;

    private String name;

    private HeroNode next;


    public HeroNode(int no, String name) {
        this.no = no;
        this.name = name;
    }

    @Override
    public String toString() {
        return "HeroNode{" +
                "no=" + no +
                ", name='" + name + '\'' +
                '}';
    }
}
