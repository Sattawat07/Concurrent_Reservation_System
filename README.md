# Cinema Reservation System ด้วย POSIX Message Queue

คู่มือนี้อธิบายการ build และรันโปรเจกต์ผ่าน Docker ตั้งแต่เริ่มต้น รวมถึงวิธีอ่านผลลัพธ์จาก Server และ `RaceTest` ระบบนี้ไม่ใช้ IP หรือ port เพราะ Server และ Client สื่อสารกันผ่าน Linux POSIX Message Queue ภายใน container เดียวกัน

## 1. สิ่งที่ต้องมี

- Docker Desktop หรือ Docker Engine ที่กำลังทำงาน
- เปิด terminal ที่โฟลเดอร์โปรเจกต์ซึ่งมี `Dockerfile`

> Docker Desktop ต้องทำงานในโหมด Linux containers เนื่องจากโปรเจกต์ใช้ Linux POSIX Message Queue และไม่สามารถรันบน Windows แบบ Native ได้

ตรวจสอบว่า Docker พร้อมใช้งาน:

```bash
docker version
```

## 2. Build Image

รันคำสั่งนี้ในโฟลเดอร์โปรเจกต์ คำสั่งเดียวนี้ทั้งสร้าง Docker image และให้ Maven คอมไพล์ Java ภายใน image จึงไม่ต้องรัน `javac` แยก:

```bash
docker build -t cinema-mq .
```

เมื่อแก้ไฟล์ `.java`, `pom.xml` หรือ `Dockerfile` ต้อง build image ใหม่ **และสร้าง container ใหม่** เพื่อให้ใช้โค้ดรุ่นล่าสุด การเปลี่ยน `sync`, `nosync` หรือจำนวน Worker ใช้แค่เริ่ม Server ใหม่ ไม่ต้อง build image ใหม่

ไฟล์ที่เกิดจากการคอมไพล์ เช่น `out/`, `target/` และ `*.class` ถูก `.gitignore` ไว้ ไม่ควร push ขึ้น Git

ตรวจสอบ image ที่สร้างแล้ว:

```bash
docker image ls cinema-mq
```

## 3. สร้าง Container

สร้าง container สำหรับใช้รัน Server, Client และ RaceTest:

```bash
docker run -d --name cinema-mq cinema-mq
```

ตรวจสอบสถานะ:

```bash
docker ps --filter name=cinema-mq
```

ถ้าเคยสร้าง container นี้แล้วและมันหยุดอยู่ ให้เปิดใหม่ด้วย:

```bash
docker start cinema-mq
```

ถ้า build image ใหม่ ต้องลบ container เดิมและสร้างใหม่เพื่อให้ container ใช้ source code รุ่นล่าสุด:

```bash
docker rm -f cinema-mq
docker run -d --name cinema-mq cinema-mq
```

## 4. รูปแบบ Message Queue ที่ใช้

ระบบใช้ Linux POSIX Message Queue สำหรับสื่อสารระหว่าง Client process และ Server process ภายใน container เดียวกัน โดยไม่ใช้ IP address หรือ port

ระบบใช้คิวสองประเภท:

| คิว | ผู้สร้าง | ผู้ส่ง | ผู้รับ | หน้าที่ |
| --- | --- | --- | --- | --- |
| `/cinema_requests` | Server | Client ทุกตัว | Worker threads | รับคำขอจาก Client ทั้งหมด |
| `/cinema_reply_<pid>_<uuid>` | Client แต่ละตัว | Worker ที่ประมวลผลคำขอ | Client เจ้าของคิว | รับผลลัพธ์เฉพาะ Client นั้น |

Client ทำหน้าที่เป็น Producer โดยสร้าง `RequestMessage` แล้วส่งเข้าสู่ `/cinema_requests` ส่วน Worker threads ทำหน้าที่เป็น Consumers และรับข้อความด้วย `mq_receive()`

`RequestMessage` ประกอบด้วย `requestId`, `clientId`, `command`, `resourceId` และ `responseQueue`

เมื่อประมวลผลเสร็จ Worker จะสร้าง `ResponseMessage` และส่งไปยัง response queue ที่ระบุอยู่ใน Request โดย Response ประกอบด้วย `requestId`, `success` และ `text`

Request Queue รองรับสูงสุด 10 ข้อความ ขนาดข้อความไม่เกิน 512 ไบต์ ส่วน Response Queue รองรับสูงสุด 10 ข้อความ ขนาดข้อความไม่เกิน 4,096 ไบต์

## 5. รูปแบบการเปิด Server

คำสั่ง Server มีรูปแบบดังนี้:

```text
Server <mode> <workerCount>
```

| รูปแบบ | ความหมาย | ใช้เมื่อ |
| --- | --- | --- |
| `sync 1` | มี Worker 1 ตัวและใช้ Semaphore | ทดลองกรณีทำงานทีละคำขอ |
| `nosync 3` | มี Worker 3 ตัวและไม่ใช้ Semaphore | สาธิต Race Condition |
| `sync 3` | มี Worker 3 ตัวและใช้ Semaphore | สาธิตการแก้ Race Condition |

- ใช้ `sync` เมื่อต้องการ **เปิด Synchronization** ด้วย `Semaphore(1)`
- ใช้ `nosync` เมื่อต้องการ **ปิด Synchronization** เพื่อสาธิต Race Condition
- การเปลี่ยนโหมดต้องหยุด Server เดิมด้วย `Ctrl+C` แล้วเริ่ม Server ใหม่

เปิด Server ใน Terminal 1 และปล่อย terminal นี้ไว้:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Server sync 3
```

เมื่อเห็นบรรทัดที่มี `mode=sync workers=3` แสดงว่า Server พร้อมรับคำขอแล้ว

กด `Ctrl+C` เมื่อต้องการหยุด Server การกดคำสั่งนี้หยุดเฉพาะโปรเซส Server แต่ container ยังทำงานอยู่ จึงสามารถเริ่ม Server โหมดใหม่ได้โดยไม่ต้อง build หรือสร้าง container ใหม่

เปิด Server ได้ครั้งละหนึ่งตัวเท่านั้น

## 6. วิธีรันแบบที่ 1: ใช้งานผ่าน Client

ต้องเปิด Server ใน Terminal 1 ก่อน จากนั้นเปิด Terminal 2 แล้วรัน:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Client Client-1
```

เปิด Client เพิ่มใน terminal อื่นได้ โดยเปลี่ยนชื่อไม่ให้ซ้ำ:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Client Client-2
```

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Client Client-3
```

Terminal 5 และ 6 เปิด Client อีกสองตัว:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Client Client-4
```

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Client Client-5
```

คำสั่งที่พิมพ์ใน Client:

| คำสั่ง | ผลลัพธ์ |
| --- | --- |
| `LIST` | แสดงสถานะที่นั่งทั้งหมด 20 ที่ |
| `STATUS 10` | ตรวจสอบที่นั่งหมายเลข 10 |
| `RESERVE 10` | จองที่นั่งหมายเลข 10 |
| `CANCEL 10` | ยกเลิกที่นั่ง ต้องใช้ Client ID ของผู้จอง |
| `QUIT` | ปิด Client ตัวนั้น แต่ Server ยังทำงานต่อ |

ตัวอย่างผลฝั่ง Client:

```text
Client-1> RESERVE 10
Server Response:
SUCCESS: Seat 10 reserved successfully.
```

ถ้า Client อื่นจองที่นั่งเดิม จะได้:

```text
FAILED: Seat 10 is already reserved.
```

## 7. วิธีรันแบบที่ 2: ทดลองพร้อมกันด้วย RaceTest

`RaceTest` สร้าง Client ตามจำนวนที่กำหนดและปล่อยให้ส่งคำสั่ง `RESERVE` พร้อมกัน รูปแบบคำสั่งคือ:

```text
RaceTest <seatId> <clientCount> <attempts>
```

ตัวอย่างนี้ให้ Client 5 ตัวแข่งกันจอง เริ่มจากที่นั่ง 10 และทดลองสูงสุด 3 รอบ:

```bash
docker exec cinema-mq java -cp 'target/classes:target/dependency/*' RaceTest 10 5 3
```

แต่ละรอบใช้ที่นั่งใหม่ เช่น ที่นั่ง 10, 11 และ 12 เพื่อไม่ให้ผลจากรอบก่อนรบกวนรอบถัดไป ชื่อ `RaceClient-2-Run1` หมายถึง Client ตัวที่ 2 ในรอบทดลองที่ 1

### การทดลองที่ 1: Worker เดียว

Terminal 1:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Server sync 1
```

Terminal 2:

```bash
docker exec cinema-mq java -cp 'target/classes:target/dependency/*' RaceTest 10 5 1
```

ผลที่ควรได้: `successes=1` และ `failures=4` เพราะ Worker ประมวลผลทีละคำขอ

### การทดลองที่ 2: สาม Worker ไม่มี Semaphore

หยุด Server เดิมด้วย `Ctrl+C` แล้วเปิด Server ใหม่ใน Terminal 1:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Server nosync 3
```

Terminal 2:

```bash
docker exec cinema-mq java -cp 'target/classes:target/dependency/*' RaceTest 10 5 5
```

ผลที่ต้องสังเกต: อย่างน้อยหนึ่งรอบอาจมี `successes` มากกว่า 1 เพราะ Worker หลายตัวตรวจพบว่าที่นั่งยังว่างพร้อมกัน ผลอาจต่างกันในแต่ละครั้งเนื่องจากลำดับการทำงานของ Thread ถูกกำหนดโดยระบบปฏิบัติการ

### การทดลองที่ 3: สาม Worker ใช้ Semaphore

หยุด Server เดิมด้วย `Ctrl+C` แล้วเปิด Server ใหม่ใน Terminal 1:

```bash
docker exec -it cinema-mq java -cp 'target/classes:target/dependency/*' Server sync 3
```

Terminal 2:

```bash
docker exec cinema-mq java -cp 'target/classes:target/dependency/*' RaceTest 10 5 3
```

ผลที่ควรได้: ทุกรอบมี `successes=1` เพราะ Semaphore อนุญาตให้ Worker เข้า Critical Section ได้ครั้งละหนึ่งตัว

## 8. วิธีอ่านผลจาก RaceTest

ตัวอย่าง:

```text
Releasing 5 clients to reserve seat 10...
RaceClient-2-Run1: SUCCESS: Seat 10 reserved successfully.
RaceClient-4-Run1: FAILED: Seat 10 is already reserved.
Attempt 1 seat 10: successes=1 failures=4 transportErrors=0
```

| ข้อความ | ความหมาย |
| --- | --- |
| `Releasing 5 clients` | Client ทั้ง 5 ตัวเริ่มส่งคำขอพร้อมกันแล้ว |
| `SUCCESS` | Client ตัวนั้นได้รับผลว่าจองสำเร็จ |
| `FAILED` | คำขอไปถึง Server แต่จองไม่สำเร็จ เช่น ที่นั่งถูกจองแล้ว |
| `successes` | จำนวน Client ที่ Server ตอบว่าจองสำเร็จ |
| `failures` | จำนวน Client ที่ได้รับคำตอบปฏิเสธจาก Server |
| `transportErrors` | จำนวนข้อผิดพลาดในการรับส่งผ่าน Message Queue ค่านี้ควรเป็น 0 |

ใน `sync` ค่าที่ถูกต้องสำหรับการจองที่นั่งเดียวกันคือ `successes=1` ส่วน `nosync` ที่มี `successes>1` คือหลักฐานของ Race Condition

## 9. วิธีอ่าน Server Log

Server log ใช้รูปแบบ `[ลำดับ เวลา] W<หมายเลข> EVENT รายละเอียด` โดยลำดับเติมศูนย์อย่างน้อย 3 หลัก และเวลาแม่นยำถึงมิลลิวินาที:

```text
[012 15:50:52.431] W3 UPDATE RaceClient-2-Run1 RESERVE seat=10 owner=RaceClient-2-Run1
```

| ส่วนของ log | ความหมาย |
| --- | --- |
| `012` | ลำดับเหตุการณ์ |
| `15:50:52.431` | เวลาที่เกิดเหตุการณ์ |
| `W3` | Worker ตัวที่ 3 (`W0` ใช้กับข้อความเริ่ม Server) |
| `RaceClient-2-Run1 RESERVE seat=10` | Client, คำสั่ง และที่นั่ง |
| `owner=...` | เจ้าของที่นั่งหลัง `UPDATE` |

ความหมายของเหตุการณ์สำคัญ:

| เหตุการณ์ | ความหมาย |
| --- | --- |
| `RECV` | Worker รับคำขอจาก request queue |
| `LOCK` | Worker ได้รับ Semaphore และเข้า Critical Section |
| `CHECK` | Worker กำลังตรวจว่าที่นั่งว่างหรือถูกจองแล้ว |
| `UPDATE` | Worker เปลี่ยนสถานะหรือเจ้าของที่นั่ง |
| `UNLOCK` | Worker ออกจาก Critical Section และคืน Semaphore |
| `RESULT` | ส่งคำตอบแล้ว โดยระบุ `SUCCESS` หรือ `FAILED` พร้อมสาเหตุสั้น ๆ |
| `ERROR` | ข้อผิดพลาดของข้อความ คิว หรือการส่งคำตอบ |

`LIST`, `STATUS` และ `QUIT` ใช้เพียง `RECV` กับ `RESULT` เพื่อให้ log กระชับ ชื่อ response queue และ request ID ปรากฏเฉพาะเมื่อส่งคำตอบไม่สำเร็จ

### ตัวอย่าง log ของโหมด sync

```text
[002 15:50:52.100] W1 RECV RaceClient-1-Run1 RESERVE seat=10
[003 15:50:52.101] W2 RECV RaceClient-2-Run1 RESERVE seat=10
[004 15:50:52.102] W1 LOCK RaceClient-1-Run1 RESERVE seat=10
[005 15:50:52.103] W1 CHECK RaceClient-1-Run1 RESERVE seat=10 AVAILABLE
[006 15:50:52.300] W1 UPDATE RaceClient-1-Run1 RESERVE seat=10 owner=RaceClient-1-Run1
[007 15:50:52.301] W1 UNLOCK RaceClient-1-Run1 RESERVE seat=10
[008 15:50:52.302] W2 LOCK RaceClient-2-Run1 RESERVE seat=10
[009 15:50:52.303] W2 CHECK RaceClient-2-Run1 RESERVE seat=10 owner=RaceClient-1-Run1
[010 15:50:52.304] W2 UNLOCK RaceClient-2-Run1 RESERVE seat=10
[011 15:50:52.305] W2 RESULT RaceClient-2-Run1 RESERVE seat=10 FAILED already reserved
```

สังเกตว่า `W2` ได้ `LOCK` หลัง `W1` ทำ `UNLOCK` จึงเห็นเจ้าของที่นั่งล่าสุดและไม่จองซ้ำ

### ตัวอย่าง log ของ Race Condition ในโหมด nosync

```text
[005 15:50:52.101] W1 CHECK RaceClient-1-Run1 RESERVE seat=10 AVAILABLE
[006 15:50:52.102] W2 CHECK RaceClient-2-Run1 RESERVE seat=10 AVAILABLE
[007 15:50:52.103] W3 CHECK RaceClient-3-Run1 RESERVE seat=10 AVAILABLE
[008 15:50:52.250] W1 UPDATE RaceClient-1-Run1 RESERVE seat=10 owner=RaceClient-1-Run1
[009 15:50:52.300] W2 UPDATE RaceClient-2-Run1 RESERVE seat=10 owner=RaceClient-2-Run1
[010 15:50:52.350] W3 UPDATE RaceClient-3-Run1 RESERVE seat=10 owner=RaceClient-3-Run1
```

Worker หลายตัวเห็น `AVAILABLE` ก่อนที่ตัวอื่นจะ UPDATE จึงมีหลาย Client ได้รับ `SUCCESS` นี่คือ Race Condition และเจ้าของที่นั่งสุดท้ายจะขึ้นอยู่กับ Worker ที่ UPDATE เป็นตัวสุดท้าย

ในโหมด `nosync` จะไม่มี `LOCK` และ `UNLOCK` เพราะไม่ได้ใช้ Semaphore

## 10. วิธีรันแบบที่ 3: Automated Tests

หยุด Server แบบ interactive ก่อน แล้วรัน:

```bash
docker exec cinema-mq mvn -q test
```

ต้องหยุด Server ก่อน เพราะชุดทดสอบจะสร้าง `/cinema_requests` และเริ่ม Server สำหรับการทดสอบเอง ถ้าคำสั่งจบโดยไม่มี error แสดงว่าชุดทดสอบผ่าน

## 11. ตรวจสอบ Message Queue

ระหว่างที่ Server หรือ Client ทำงาน สามารถดูคิวได้ด้วย:

```bash
docker exec cinema-mq ls -l /dev/mqueue
```

เมื่อ Server ทำงานควรเห็น:

```text
cinema_requests
```

เมื่อมี Client ทำงาน จะเห็นคิวชื่อประมาณนี้เพิ่มขึ้น:

```text
cinema_reply_1234_a1b2c3...
```

หลัง Client ปิดด้วย `QUIT` response queue ของ Client นั้นควรหายไป และหลังหยุด Server ด้วย `Ctrl+C` คิว `cinema_requests` ควรหายไป

## 12. ปัญหาที่พบบ่อย

### Client เปิดไม่ได้และพบ `cinema_requests` หรือ `No such file`

ยังไม่ได้เปิด Server ให้เปิด Server ในอีก terminal ก่อน แล้วจึงเปิด Client หรือ RaceTest

### Server แจ้งว่า `/cinema_requests` มีอยู่แล้ว

อาจมี Server อีกตัวทำงานอยู่ ตรวจสอบด้วย:

```bash
docker top cinema-mq
docker exec cinema-mq ls -l /dev/mqueue
```

อย่าเปิด Server ซ้อนกันและอย่าลบคิวของ Server ที่ยังทำงานอยู่

### พบ `Timed out waiting for response`

Client ส่งคำขอแล้วแต่ไม่ได้รับคำตอบภายใน 15 วินาที ให้ตรวจว่า Server ยังทำงานและไม่มี error ใน Terminal 1

### Docker แจ้งว่าชื่อ container ถูกใช้แล้ว

ถ้า container เดิมหยุดอยู่:

```bash
docker start cinema-mq
```

ถ้าต้องการสร้างใหม่จาก image ล่าสุด:

```bash
docker rm -f cinema-mq
docker run -d --name cinema-mq cinema-mq
```

## 13. ปิดระบบ

1. พิมพ์ `QUIT` ใน Client ทุกตัว
2. กด `Ctrl+C` ใน Terminal ของ Server
3. หยุดและลบ container:

```bash
docker stop cinema-mq
docker rm cinema-mq
```

Image ยังอยู่และนำกลับมาใช้ได้ หากต้องการลบ image ด้วย:

```bash
docker image rm cinema-mq
```
