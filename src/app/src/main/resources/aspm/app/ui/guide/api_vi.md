Trang này dành cho người nối một pipeline, một script hay một hệ thống khác vào nền tảng. Bảng ở cuối
trang được **sinh ra từ chính operation registry của nền tảng** — đúng cái danh sách mà dispatcher thực
thi — nên nó không thể mô tả một endpoint không tồn tại, cũng không thể bỏ sót một endpoint đang chạy.

## 1. Có hai cửa, và chúng nhận hai loại thông tin xác thực khác nhau

**`/api/v1/…` là cửa dành cho máy.** Request có chữ ký, phát ra từ một service credential. Đây là cửa
một CI pipeline dùng.

**`/api/ui/…` là cửa riêng của giao diện này.** Nó nhận cookie phiên của trình duyệt và tồn tại để phục
vụ giao diện. Nó được nhắc ở đây vì bạn sẽ thấy nó trong tab network của trình duyệt, chứ không phải vì
nó là một bề mặt tích hợp được hỗ trợ — hình dạng của nó bám theo từng màn hình và thay đổi cùng chúng.

Hãy viết tích hợp trên `/api/v1`.

## 2. Không có API key, và đó là chủ ý

Một bearer token là thứ hoạt động với bất kỳ ai cầm được nó, từ bất kỳ đâu, cho tới khi có người phát
hiện. Token của pipeline rò vào build log, vào ảnh chụp màn hình, vào một bản fork của repository. Nên
nền tảng không cấp loại đó.

Thay vào đó, mỗi request được service credential ký. Bí mật không bao giờ đi trên đường truyền; thứ đi
trên đường truyền là chữ ký của **request đó**, và chữ ký ấy vô dụng với mọi request khác.

### Lấy credential

Settings → Access → Service credentials → Issue. Bạn chọn principal mà nó đóng vai, phạm vi tổ chức nó
bị ghim vào, và các quyền nó khai báo. Bí mật được hiện **đúng một lần**; hệ thống chỉ lưu digest, nên
không có lần thứ hai và không có nút "xem lại".

Hai điều cần biết trước khi dùng:

- **Khoá ký là SHA-256 của bí mật**, không phải bản thân bí mật. Phản hồi có ghi rõ điều này.
- **Quyền thực tế là phần giao** giữa những gì credential khai báo và những gì principal đứng sau nó
  có. Một credential không thể mở rộng quyền của principal — nên cấp credential không phải là đường
  vòng qua vai trò.

### Ký một request

```
canonical = METHOD \n PATH \n SHA256(body) \n TIMESTAMP \n NONCE
signature = HMAC-SHA256(khoá_ký, canonical)
```

Rồi gửi:

```
Authorization: ASPM-HMAC-SHA256 key=<key id>, ts=<unix seconds>, nonce=<hex>, signature=<hex>
x-aspm-content-sha256: <sha256 hex của body thô>
Content-Type: application/json
Idempotency-Key: <khoá của bạn>
```

Digest của body nằm trong phần được ký, và nền tảng băm lại chính body nó nhận được rồi mới so sánh.
Thiếu bước đó thì chữ ký chỉ phủ một lời hứa về body.

Dấu thời gian phải nằm trong **năm phút** so với đồng hồ của nền tảng, và nonce phải chưa từng dùng —
hai điều kiện đó cùng nhau chặn việc phát lại một request đã bị bắt được.

### Điều một service credential không làm được

Nó không bao giờ được step-up. Các operation thuộc lớp **C** và **E** — tiết lộ dữ liệu hạn chế và cấu
hình tenant — đòi hỏi yếu tố thứ hai còn tươi, nên không request có chữ ký nào thực hiện được. Vì vậy
asset type, org node type và criticality tier phải do một người tạo trong giao diện trước; sau đó
pipeline mới dùng được chúng.

## 3. Idempotency

Mọi thao tác ghi đều mang `Idempotency-Key`. Gửi lại cùng một khoá thì lần thứ hai trả về kết quả của
lần đầu, chứ không làm lại công việc.

Đây không phải phép lịch sự. Một pipeline hết thời gian chờ phản hồi rồi thử lại là chuyện bình thường,
và nếu không có khoá thì lần thử lại sẽ phát hiện lại toàn bộ finding trong báo cáo — điều đó mở lại các
finding đã đóng và tăng số lần tái phát. Một lần gửi bị biến thành "cái này cứ quay lại mãi".

Hãy dùng khoá dẫn xuất từ chính thứ bạn gửi — mã build, commit, số lần chạy. Khoá ngẫu nhiên mỗi lần thử
làm cơ chế này mất tác dụng hoàn toàn.

## 4. Các câu trả lời có nghĩa gì

**`404` có thể nghĩa là "bạn không được phép".** Việc từ chối và một định danh không tồn tại được cố ý
làm cho không phân biệt được. Nếu chúng khác nhau, một caller không có quyền có thể dò định danh rồi đọc
mã trả về để vẽ ra bản đồ hệ thống. Nên `404` ở một đường dẫn bạn tin là có nghĩa là: hoặc nó không tồn
tại, hoặc phạm vi của credential không với tới nó.

**`403` nghĩa là credential đã xác thực và bị từ chối**, và body nói rõ vì sao — `STEP_UP_REQUIRED` với
operation lớp C hoặc E, `CREDENTIAL_CHANGE_REQUIRED` với principal đang bị đánh dấu phải đổi mật khẩu.

**`422` nghĩa là tài liệu đã được hiểu và bị từ chối**, kèm mã lỗi và thông điệp bạn có thể xử lý. Báo
cáo quét hoặc bill of materials bị từ chối sẽ trả về ở đây.

**`401` nghĩa là chữ ký không hợp lệ**, hoặc dấu thời gian ngoài cửa sổ cho phép, hoặc nonce bị dùng
lại. Nó không bao giờ mang nghĩa "sai quyền".

## 5. Gửi kết quả quét

`POST /api/v1/finding-imports` nhận tài liệu SARIF 2.1.0.

```json
{
  "application": "Booking Engine",
  "project": "Reservations",
  "repository": "booking-payments-api",
  "document": { "version": "2.1.0", "runs": [ … ] }
}
```

Địa chỉ ba phần là cách nền tảng quyết định các finding thuộc về tài sản nào. **Tài liệu không được
quyền tự khai**: báo cáo quét là dữ liệu chịu ảnh hưởng của kẻ tấn công, và một báo cáo tự khai mục tiêu
có thể ghi finding vào repository của người khác.

Một parser phủ semgrep, mobsfscan và CodeQL. Kết quả trả về được đếm **theo từng loại xử lý** — mới nạp,
đã biết, mở lại, gộp, giữ lại — bởi vì "đã xử lý 42 bản ghi" là con số không nói lên điều gì. Mọi cảnh
báo đều nằm trong phản hồi chứ không chỉ ghi log, vì bạn là bên duy nhất có thể xử lý một khoảng trống
ánh xạ hay một bản ghi bị giữ lại.

Hai hành vi nên biết trước lần đẩy đầu tiên:

- **Một điểm yếu quay lại sau khi đã có người đóng thì được mở lại**, và số lần tái phát tăng lên. Đó là
  điều quan trọng nhất endpoint này báo cho bạn.
- **Không có gì bị đóng bởi một lần import.** Một lần quét không còn báo điểm yếu có thể vì phạm vi bị
  thu hẹp, vì lần quét hỏng, hoặc vì chạy trên một revision khác. Việc đóng vẫn là quyết định của con
  người, kèm phương pháp kiểm chứng.

## 6. Gửi bill of materials

`POST /api/v1/sbom-submissions` nhận CycloneDX hoặc SPDX, địa chỉ hoá theo cùng cách. Phản hồi mang theo
điểm chất lượng và mọi cảnh báo, vì người gửi là bên duy nhất sửa được một tài liệu chất lượng thấp.

Giữa các lần gửi, nền tảng mù — và nó nói ra điều đó thay vì hiển thị một con số cũ như thể là hiện tại.
Đó là lý do sức khoẻ gửi dữ liệu được theo dõi theo từng credential: một pipeline bị từ chối hai trăm lần
và một pipeline đơn giản là chưa chạy trông giống hệt nhau nếu chỉ nhìn dữ liệu, mà hai tình huống ấy cần
hai phản ứng ngược nhau.

## 7. Đọc dữ liệu ra

Các endpoint đọc dưới `/api/v1` bị giới hạn phạm vi đúng như một con người: nhánh tổ chức mà credential
bị ghim vào, và không gì ngoài nhánh đó. Một collection trả về những gì phạm vi của bạn với tới, không
phải những gì tồn tại.

Lọc bằng query parameter. `org` bao gồm cả cây con ở mọi nơi nó xuất hiện — nêu tên một division nghĩa là
division đó và mọi thứ bên dưới — và nó mang đúng một nghĩa trên mọi màn hình chấp nhận nó.

## 8. Tạo dữ liệu nền: tổ chức, ứng dụng, project và repository

Hai tài nguyên phủ hết, vì nền tảng có một cây tổ chức duy nhất và một aggregate tài sản duy nhất,
chứ không phải mỗi loại một kho riêng.

### Một node tổ chức

`POST /api/v1/org-nodes`

```json
{
  "type_id": "<id của một org node type>",
  "parent_id": "<node cấp trên, bỏ trống nếu là gốc>",
  "name": "Payments Platform Team",
  "external_reference": "định danh của riêng bạn",
  "criticality_mode": "INHERITED"
}
```

`type_id` lấy từ `GET /api/v1/org-node-types`. Loại node là cấu hình của tenant, nên nền tảng không
áp đặt một bộ cấp cố định — division, business unit, product hay team đều là cùng một bảng với các
dòng type khác nhau. Đó là thứ cho phép cây sâu đúng bằng tổ chức thật của bạn.

Tạo một LOẠI là lớp E nên phải có người thật: `POST /api/v1/org-node-types` credential không gọi được.
Hãy tạo các cấp một lần trong giao diện; sau đó pipeline tạo node bên dưới thoải mái.

### Ứng dụng, project, service, repository

Cả bốn đều là asset, và `type_id` quyết định nó là gì.

`POST /api/v1/assets`

```json
{
  "type_id": "<id của một asset type>",
  "display_name": "Payments Portal",
  "owning_node_id": "<node tổ chức chịu trách nhiệm>",
  "criticality_mode": "INHERITED",
  "exposure_declared": "INTERNAL_ONLY"
}
```

`GET /api/v1/asset-types` liệt kê những gì tenant này định nghĩa — ở deployment hiện tại là
`APPLICATION`, `SERVICE`, `FEATURE`, `PROJECT`, `REPOSITORY` và `DOMAIN`. **Không có endpoint riêng
để "tạo project"**: project là một asset có type `PROJECT`, nằm trong một application. Đây là chủ ý —
năm kho song song là năm chỗ để cùng một repository tồn tại dưới năm cái tên hơi khác nhau.

`owning_node_id` là thứ làm cho asset hiện ra với bất kỳ ai: phạm vi được dẫn xuất từ cây tổ chức, nên
một asset không có chủ sẽ không xuất hiện trên dashboard của ai cả. Nền tảng vẫn nhận asset không có
chủ và báo là "chưa có chủ", thay vì từ chối — vì một asset bị từ chối là phần phủ sóng bạn âm thầm
không có.

**Một credential bị ghim vào một phần của cây bắt buộc phải chỉ định một nút nằm trong phần đó**, và
bắt buộc phải chỉ định. Một nút ngoài scope trả về `404` — đúng câu trả lời như một nút không tồn tại,
nên không thể dùng lời từ chối để dò ra cây tổ chức có những gì. Bỏ trống trường này cũng bị từ chối
vì cùng lý do: asset sẽ nằm ngoài mọi scope, kể cả scope của bạn, và bạn không đọc lại được chính thứ
mình vừa tạo. Credential ghim vào toàn bộ tenant thì có thể cố ý tạo asset không chủ; nó sẽ xuất hiện
trong hàng đợi asset chưa có chủ chứ không lên dashboard.

**Quan hệ chứa không đặt ở đây.** Application nào chứa project nào là một cạnh trong đồ thị tài sản, và
hai cửa SBOM / báo cáo quét tạo ra cạnh đó như hệ quả của việc nêu địa chỉ ba phần. Không có endpoint
v1 nào ghi cạnh trực tiếp.

### Cập nhật

`PATCH /api/v1/org-nodes/{id}` và `PATCH /api/v1/assets/{id}`, kèm `row_version` lấy từ lần đọc gần
nhất. Sai phiên bản thì bị từ chối chứ không ghi đè: hai hệ thống cùng sửa một dòng là chuyện bình
thường với tích hợp, và last-write-wins làm mất thay đổi của bên kia trong im lặng.

## 9. Những gì API không làm được, và vì sao

Nêu rõ, vì "không có" rất khó phân biệt với "chưa viết tài liệu".

- **Không có endpoint tạo yêu cầu đánh giá.** Một request mang theo phạm vi, cam kết sẵn sàng và cách
  xử lý thông tin đăng nhập mà form intake thu thập cùng lúc; API có phía đọc
  (`GET /api/v1/requests`) và phía chuyển trạng thái (`POST /api/v1/requests/{id}/transitions`), nhưng
  không có phía tạo.
- **Không có endpoint ghi một finding đơn lẻ.** Finding vào qua cửa import, đó là thứ giữ cho việc khử
  trùng, tính vân tay và nguồn gốc nằm trên một đường duy nhất. Một finding gửi thẳng sẽ không có
  import session phía sau và sẽ vô hình với dashboard CI/CD, vốn chỉ có đúng một vị từ trung thực là
  nguồn gốc đó.
- **Không có endpoint tạo tenant, role hay permission.** Role là cấu hình tenant (lớp E); catalogue
  quyền do sản phẩm cố định và không ghi được.
- **Không có gì tự động đóng một finding.** `PATCH /api/v1/findings/{id}` sửa được các trường, còn các
  chuyển trạng thái đóng nó là quyết định của con người, kèm phương pháp kiểm chứng.

## 10. Đọc, lọc và phân trang

Mọi collection dưới `/api/v1` trả về cùng một khung:

```json
{
  "items": [ … ],
  "has_more": true,
  "next_cursor": "eyJzb3J0IjoiMjAyNi0wOC0xNSIsImlkIjoiMDE5ZmYuLi4ifQ"
}
```

Phân trang bằng **con trỏ, không phải số trang**:

```
GET /api/v1/findings?limit=50
GET /api/v1/findings?limit=50&cursor=<next_cursor của lần trước>
```

Đọc cho tới khi `has_more` là false. Không có tham số `page` và không có tổng số — số trang trên một
bảng đang được ghi vào sẽ bỏ sót và lặp dòng, còn một con số tổng trên tập đã lọc theo phạm vi tốn thêm
một lần quét đầy đủ để cho ra con số đã cũ ngay khi nó về tới.

Lọc là **so khớp chính xác trên một trường có tên**, và tập trường khác nhau theo từng endpoint — bảng
bên dưới liệt kê theo từng operation, sinh từ chính khai báo mà bộ kiểm tra request thực thi.

```
GET /api/v1/findings?state=OPEN&limit=50
```

- Nhiều bộ lọc kết hợp bằng AND. Bề mặt này không có OR, không có khoảng, không có khớp một phần; các
  dashboard làm nhiều hơn, và chúng làm qua `/api/ui` — không phải hợp đồng này.
- **Lọc trên một trường không được khai là lọc được thì bị TỪ CHỐI**, không phải bị bỏ qua. Đây là chủ
  ý: một tham số bị âm thầm bỏ đi là cách một tích hợp báo cáo toàn bộ hệ thống trong khi tin rằng mình
  chỉ báo cáo một division.
- `org` bao gồm cả cây con ở mọi nơi nó xuất hiện.
- Phản hồi chứa những gì phạm vi của credential với tới. Nó không phải một trang của tất cả.

## 11. Một request đầu tiên hoàn chỉnh

Tất cả những điều trên, gói trong một ví dụ chạy được. Đây là lời gọi đúng nhỏ nhất.

```bash
KEY_ID="key id của bạn"
SECRET="bí mật được hiện đúng một lần khi cấp key"
# KHOÁ KÝ là SHA-256 của bí mật, dạng hex.
SIGNING_KEY=$(printf %s "$SECRET" | sha256sum | cut -d' ' -f1)

PATH_ONLY="/api/v1/findings"          # KHÔNG kèm query string — xem bên dưới
BODY=""                                # GET có body rỗng, và vẫn phải băm
BODY_SHA=$(printf %s "$BODY" | sha256sum | cut -d' ' -f1)
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)

CANONICAL=$(printf '%s\n%s\n%s\n%s\n%s' "GET" "$PATH_ONLY" "$BODY_SHA" "$TS" "$NONCE")
SIG=$(printf %s "$CANONICAL" | openssl dgst -sha256 -mac HMAC -macopt hexkey:"$SIGNING_KEY" -r \
      | cut -d' ' -f1)

curl -sS "https://host-cua-ban/api/v1/findings?limit=5" \
  -H "Accept: application/json" \
  -H "x-aspm-content-sha256: $BODY_SHA" \
  -H "Authorization: ASPM-HMAC-SHA256 key=$KEY_ID, ts=$TS, nonce=$NONCE, signature=$SIG"
```

Bốn chi tiết quyết định lần gọi đầu tiên của bạn có chạy hay không:

- **Đường dẫn được ký KHÔNG kèm query string.** `?limit=5` vẫn được gửi nhưng KHÔNG nằm trong chuỗi
  canonical. Ký `/api/v1/findings?limit=5` cho ra một chữ ký trông rất hợp lệ và một mã `401`.
- **Body vẫn được băm kể cả khi rỗng.** SHA-256 của chuỗi rỗng là
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; hãy gửi và ký giá trị đó.
- **Nonce dài 8 đến 128 ký tự** và không được lặp lại. Mười sáu byte ngẫu nhiên dạng hex là 32 ký tự,
  vừa đủ.
- **Dấu thời gian tính bằng giây Unix và phải nằm trong năm phút** so với đồng hồ nền tảng. Một
  container lệch giờ sẽ hỏng mọi request với `401` mà thông điệp không hề nhắc tới đồng hồ — hãy kiểm
  tra nó đầu tiên khi một chữ ký trông đúng vẫn bị từ chối.

Thao tác ghi thêm hai header: `Content-Type: application/json` và `Idempotency-Key: <khoá của bạn>`.

## 12. Lớp giới hạn tần suất

Mỗi operation mang một lớp, và bảng bên dưới hiển thị nó: `READ`, `WRITE`, `SENSITIVE`, `BULK`,
`INGEST` hoặc `ANON`. Chúng tồn tại để một lần đẩy dữ liệu và một lần mở dashboard không tiêu vào hạn
mức của nhau — một lần gửi hàng loạt ban đêm không được phép làm cạn phần mà một người mở trang cần.

**Hiện mới được khai báo, chưa được cưỡng chế.** Chưa có hạn mức nào được áp và chưa thao tác nào trả
về `429`. Lớp này được nêu ở đây vì nó là hợp đồng mà client nên được xây theo — một client thử lại
việc đẩy dữ liệu mà không có backoff thì hôm nay vẫn chạy và sẽ hỏng khi bộ giới hạn xuất hiện — nhưng
người đọc không được hiểu nhầm đây là một biện pháp đang chạy. Hiện không có gì giới hạn tốc độ gọi
của một credential.

## 13. Các giá trị mà những trường này nhận

Một trường chỉ nhận một tập cố định sẽ từ chối giá trị nằm ngoài tập đó, nên các tập được liệt kê ở
đây. Có hai loại, và chúng được quản trị theo cách khác nhau.

**Cố định theo sản phẩm.** Giống nhau ở mọi lần triển khai và được cơ sở dữ liệu ràng buộc:

| Trường | Giá trị hợp lệ |
|---|---|
| `criticality_mode` trên org node hoặc asset | `ASSIGNED`, `INHERITED` |
| `exposure_declared` trên asset | `INTERNET_PUBLIC`, `PARTNER_B2B`, `INTERNAL_ONLY`, `AIR_GAPPED` |
| `lifecycle_state` trên asset | `DISCOVERED`, `ACTIVE`, `DEPRECATED`, `RETIRED` |
| `finding_class` | `CODE`, `DEPENDENCY`, `RUNTIME`, `INFRASTRUCTURE`, `SECRET`, `MANUAL`, `CONFIGURATION` |
| `state` trên finding | `OPEN`, `CLOSED` |
| `lifecycle_state` trên finding | `OPEN`, `FIXED`, `REOPEN`, `ACCEPTANCE_REQUESTED`, `CLOSED`, `ACCEPTED_RISK` |
| `assessment_context` trên finding | `INTERNAL_PENTEST`, `EXTERNAL_PENTEST`, `REDTEAM_INTERNAL`, `REDTEAM_EXTERNAL`, `AUTOMATED_SCAN`, `BUG_BOUNTY`, `INCIDENT` |

Một finding mang **hai** trường trạng thái và chúng không trùng lặp. `state` là trục thô mà mọi số
đếm và dashboard tổng hợp theo; `lifecycle_state` là vị trí của nó trong quy trình. Hai trục bị ràng
buộc phải khớp nhau — `OPEN`, `FIXED`, `REOPEN` và `ACCEPTANCE_REQUESTED` đúng là các giá trị
lifecycle ứng với `state` bằng `OPEN` — nên hãy lọc theo `state` khi bạn muốn "vẫn còn tính vào nợ",
và đọc bản ghi chi tiết khi bạn muốn biết vì sao.

`criticality_mode: "INHERITED"` nghĩa là bậc trọng yếu lấy từ org node bên trên; `ASSIGNED` bắt buộc
kèm `criticality_tier_id` trong cùng body, thiếu là bị từ chối.

**Cấu hình theo tenant.** Những thứ này không có tập cố định, và việc mã hoá cứng chúng chính là sai
lầm mà nền tảng này được xây để tránh. Hãy đọc, đừng đoán:

| Cái gì | Lấy từ đâu |
|---|---|
| Loại org node (khối, đơn vị kinh doanh, đội, …) | `GET /api/v1/org-node-types` |
| Loại asset (ứng dụng, dịch vụ, project, repository, …) | `GET /api/v1/asset-types` |
| Bậc trọng yếu | `GET /api/v1/criticality-tiers` |
| Trạng thái yêu cầu đánh giá và các chuyển tiếp | `GET /api/v1/requests/{id}/transitions` trả về những gì hợp lệ từ vị trí hiện tại |
| Mức nghiêm trọng, trạng thái work item, trường tuỳ biến | Cấu hình tenant; không ghi được từ cửa dành cho máy |

Quy trình yêu cầu đáng được nhấn mạnh. `POST /api/v1/requests/{id}/transitions` nhận một `state`, và
giá trị nào hợp lệ phụ thuộc vào quy trình mà tenant cấu hình **và** vào trạng thái hiện tại của yêu
cầu. Cách được hỗ trợ là hỏi tập hợp lệ trước; đoán một tên trạng thái rồi đọc lỗi từ chối thì không,
vì một lời từ chối cố ý không liệt kê ra thứ lẽ ra sẽ được chấp nhận.

## 14. Tìm các định danh bạn cần

Không chỗ nào trong API này nhận tên khi có thể nhận định danh, nên lần tích hợp đầu tiên phần lớn là
một chuỗi thao tác đọc. Theo thứ tự:

1. `GET /api/v1/org-node-types` → chọn loại cho cấp bạn đang tạo.
2. `GET /api/v1/org-nodes` → tìm nút cha, hoặc bỏ `parent_id` nếu tạo nút gốc.
3. `POST /api/v1/org-nodes` → phản hồi mang `id` và `row_version` mới.
4. `GET /api/v1/asset-types` → chọn `APPLICATION`, `REPOSITORY` hoặc bất kỳ loại nào tenant này định nghĩa.
5. `POST /api/v1/assets` với `owning_node_id` là nút từ bước 3.
6. Đẩy finding hoặc bill of materials theo địa chỉ ba phần, không theo id của asset — các cửa nạp dữ
   liệu tự phân giải và tự tạo asset repository.

Bước 1 đến 5 chỉ cần khi bạn chủ động tạo phần estate. Một pipeline chỉ đẩy kết quả quét thì không
cần bước nào cả: địa chỉ ở mục 5 là đủ, và asset sẽ tự xuất hiện.
