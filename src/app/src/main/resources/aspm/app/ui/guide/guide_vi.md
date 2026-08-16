Tài liệu này mô tả mỗi màn hình dùng để làm gì, nó sẽ cho bạn biết và không cho bạn biết điều gì, và
vì sao đôi khi nó từ chối yêu cầu của bạn. Tài liệu viết cho tất cả người dùng, không riêng quản trị
viên — nên phần lớn nội dung áp dụng cho bạn bất kể vai trò của bạn được đặt tên là gì trong tổ chức.

Nếu chỉ đọc được một phần, hãy đọc hai mục đầu. Chúng giải thích hai hành vi thường gây bất ngờ: vì
sao thanh điều hướng của bạn ngắn hơn của đồng nghiệp, và vì sao một con số đôi khi vắng mặt thay vì
bằng không.

## 1. Nền tảng này dùng để làm gì

Nền tảng lưu giữ trạng thái an toàn phần mềm của tổ chức: các ứng dụng và dịch vụ đang chạy, ai chịu
trách nhiệm cho từng thứ, các điểm yếu được phát hiện, các thành phần phụ thuộc được đóng gói kèm, và
toàn bộ công việc khắc phục những điểm yếu đó.

Ba hệ quả đi theo bạn khắp sản phẩm.

**Đây là hệ thống làm việc, không chỉ là bảng điều khiển.** Yêu cầu đánh giá, phát hiện, bình luận,
tuyên bố đã khắc phục và việc kiểm chứng tuyên bố đó đều nằm ở đây. Nếu cuộc trao đổi về một phát hiện
diễn ra ở nơi khác, mọi con số nền tảng đưa ra đều được tính trên một hồ sơ không đầy đủ.

**Nền tảng không bao giờ đọc mã nguồn của bạn.** Nó không sao chép kho mã và không giữ thông tin xác
thực Git. Những gì nó biết về thành phần phụ thuộc đến từ việc một quy trình dựng phần mềm đã gửi lên
bản kê khai thành phần phần mềm. Giữa hai lần gửi, nền tảng không nhìn thấy gì — và nó nói thẳng điều
đó thay vì hiển thị số liệu cũ như thể đang là hiện tại.

**Nền tảng phân biệt "đã đo và sạch" với "chưa đo".** Đây là quy tắc quan trọng nhất của sản phẩm và
mục 2 chỉ nói về điều này.

## 2. Đọc các con số một cách trung thực

Ở phần lớn công cụ, "không có kết quả" và "chưa đo" trông giống hệt nhau. Ở đây thì không.

- **Số không đã đo được** hiển thị bằng chữ số: `0`.
- **Chỉ số chưa đo không mang chữ số nào cả.** Bạn sẽ thấy chữ — *Chưa đo*, *Chưa từng đo*, *Không
  hiển thị cho bạn*, *Không khả dụng* — ở đúng vị trí đáng lẽ là một con số.

Sự phân biệt này là cố ý. Một bảng điều khiển màu xanh vì chưa ai nhìn còn tệ hơn không có bảng điều
khiển nào, bởi vì sẽ có người ra quyết định dựa trên nó.

Bên cạnh các con số luôn có hai dữ kiện hỗ trợ:

- **Độ bao phủ** — bao nhiêu tài sản trong phạm vi đang xem đã thực sự được đo, trên tổng số hiện có.
  "3 trên 9 tài sản đã đo" nghĩa là sáu tài sản còn lại không nói cho bạn điều gì.
- **Độ tươi mới** — phép đo diễn ra khi nào. Số liệu thành phần phụ thuộc từ bốn tháng trước mô tả
  phần mềm của bốn tháng trước.

**Một lần quét thất bại không bao giờ đóng phát hiện.** Nếu một lần nạp dữ liệu thất bại, các phát
hiện vẫn mở và độ bao phủ giảm xuống. Không có con số nào tự tốt lên chỉ vì phép đo ngừng đến.

Khi bạn xuất một màn hình ra bảng tính, quy tắc trên vẫn áp dụng: bản xuất mang đúng những dòng và
đúng độ bao phủ của màn hình gốc, không phải một phiên bản gọn gàng hơn.

## 3. Đăng nhập

Quản trị viên của tổ chức tạo tài khoản cho bạn. Bạn đăng nhập bằng tên đăng nhập hoặc địa chỉ thư
điện tử cùng mật khẩu.

**Bắt buộc có yếu tố thứ hai.** Lần đăng nhập đầu tiên bạn sẽ được đưa tới màn hình đăng ký: quét mã
QR bằng ứng dụng xác thực, rồi xác nhận bằng mã sáu chữ số. Bạn không thể tới bất kỳ màn hình nào khác
cho đến khi hoàn tất — đây không phải một tùy chọn có thể từ chối. Hãy lưu các mã khôi phục được cấp ở
nơi khác với máy bạn vừa đăng nhập; mỗi mã dùng được một lần.

**Bạn có thể bị yêu cầu đổi mật khẩu ngay lập tức.** Tài khoản do quản trị viên tạo, và tài khoản vừa
được đặt lại thông tin xác thực, sẽ dừng ở màn hình đổi mật khẩu cho tới khi mật khẩu mới được đặt.

**Mật khẩu ưu tiên độ dài.** Mặc định tối thiểu mười hai ký tự và không có quy tắc thành phần — không
bắt buộc ký tự đặc biệt, không bắt buộc chữ số. Một cụm mật khẩu dài vừa dễ nhớ hơn vừa khó tấn công
hơn một mật khẩu ngắn có thêm dấu `!` ở cuối. Mật khẩu của bạn được đối chiếu với kho thông tin xác
thực đã bị lộ, cả khi đặt lẫn khi đăng nhập; nếu trùng, bạn sẽ được yêu cầu chọn mật khẩu khác.

**Phiên làm việc có hạn.** Mặc định một phiên kéo dài tám giờ tuyệt đối và ba mươi phút không hoạt
động; mười hai giờ là mức tối đa mà bất kỳ triển khai nào được phép cấu hình. Khi phiên hết hạn bạn
quay về màn hình đăng nhập và trang đang xem được ghi nhớ.

### Xác thực nâng bậc: khi bị hỏi lại mã

Một số thao tác sẽ hỏi lại yếu tố thứ hai dù bạn đã đăng nhập. Đó là những thao tác thay đổi cơ sở mà
mọi quyết định khác được tính từ đó, hoặc tiết lộ thứ gì đó bị hạn chế:

- soạn một vai trò hoặc cấp vai trò cho ai đó,
- đặt lại thông tin xác thực của người khác,
- cấp thông tin xác thực cho quy trình dựng phần mềm,
- thay đổi loại cấu trúc, bậc trọng yếu hoặc loại tài sản,
- thay đổi chính sách rà soát định kỳ hoặc cấu hình nhà cung cấp AI,
- phê duyệt việc chấp nhận rủi ro còn lại.

Giao diện có thể cảnh báo bạn *trước khi* bắt đầu điền biểu mẫu rằng thao tác sẽ cần mã mới — mất một
biểu mẫu đang điền dở vì bị chuyển hướng còn tệ hơn là được báo trước. Cảnh báo đó chỉ là lời khuyên;
rào chắn thật nằm ở máy chủ và áp dụng dù bạn có thấy cảnh báo hay không.

## 4. Di chuyển trong sản phẩm

**Thanh điều hướng** được nhóm theo công việc bạn đang làm chứ không theo cách dữ liệu được lưu: điều
gì đang diễn ra, hạ tầng gồm những gì, và cái gì cấu hình nền tảng.

**Chỉ báo phạm vi** ở thanh trên cùng cho biết phần tổ chức bạn đang xem. Mọi con số trên mọi màn hình
đều được tính trên phạm vi đó và không rộng hơn. Nếu bạn không chắc mình đang đọc lát cắt nào, câu trả
lời nằm ở đây.

**Bảng lệnh** mở bằng Ctrl+K (⌘K trên máy Mac) từ bất cứ đâu, hoặc bằng cách bấm vào ô tìm kiếm ở
thanh trên. Nó liệt kê những mục bạn có thể tới. Tìm một đối tượng theo mã chưa được xây dựng, và hộp
thoại nói rõ điều đó thay vì trả về rỗng để bạn kết luận rằng đối tượng không tồn tại.

**Giao diện và mật độ** nằm ở thanh trên: sáng, tối, tương phản cao, và chiều cao dòng thoải mái hoặc
gọn. Các lựa chọn này áp dụng cho phiên hiện tại.

**Bàn phím.** Danh sách di chuyển bằng `j` và `k` hoặc phím mũi tên. Escape đóng hộp thoại. Không một
chức năng nào trong sản phẩm chỉ dùng được bằng chuột, và không chức năng nào chỉ dùng được khi bật
JavaScript.

**Tài khoản của bạn** mở từ thanh trên. Ở đó có tên đăng nhập, thư điện tử, tên hiển thị, các vai trò
bạn đang giữ, trạng thái đăng ký yếu tố thứ hai, và mọi phiên đang đăng nhập dưới danh nghĩa bạn —
kèm địa chỉ và trình duyệt của từng phiên. Hãy chấm dứt bất kỳ phiên nào bạn không nhận ra.

## 5. Vì sao thanh điều hướng của bạn khác của đồng nghiệp

Hai thứ độc lập quyết định điều bạn nhìn thấy. Nhầm lẫn giữa chúng là nguyên nhân phổ biến nhất của
câu hỏi "vì sao tôi không thấy X".

**Quyền quyết định bạn được làm *loại việc gì*.** Đọc phát hiện, phân loại phát hiện, gửi yêu cầu,
quản lý vai trò — mỗi thứ là một quyền có tên riêng. Quản trị viên gom các quyền thành vai trò và cấp
cho bạn một hoặc nhiều vai trò.

**Phạm vi quyết định các quyền đó *chạm tới đối tượng nào*.** Một vai trò được cấp cho bạn trên một
phần của tổ chức: toàn bộ tổ chức, một nút và mọi thứ bên dưới nó, hoặc riêng một nút.

Bạn cần cả hai. Nhìn thấy một dự án không có nghĩa bạn được yêu cầu công việc trên nó; được phép phân
loại không có nghĩa bạn được phân loại phát hiện của bộ phận khác.

Ba hành vi sau đây là hệ quả, và đều có chủ đích:

- **Mục bạn không tới được sẽ không được liệt kê.** Thanh điều hướng ẩn nó thay vì hiện rồi từ chối,
  vì một thanh điều hướng đầy liên kết hỏng dạy người dùng đừng tin thanh điều hướng.
- **Một yêu cầu bị từ chối trả lời "không tìm thấy", không phải "không được phép".** Hai trường hợp
  này cố ý không phân biệt được. Nếu chúng khác nhau, ai đó có thể dò ra danh mục dự án của bộ phận
  khác chỉ bằng cách quan sát mã lỗi trả về.
- **Bộ chọn đã lọc là tiện ích, không bao giờ là kiểm soát.** Khi một danh sách thả xuống chỉ đưa ra
  những dự án bạn được dùng, đó là để bạn đỡ phải cuộn. Máy chủ vẫn kiểm tra lại mọi thứ bạn gửi lên.

**Nếu bạn tin rằng mình đáng được thấy một thứ mà lại không thấy,** hãy hỏi người quản trị quyền truy
cập xem bạn có quyền đó không và phạm vi được cấp có phủ đúng phần tổ chức cần thiết không. Trên màn
hình tài khoản, dòng "Chưa được gán" ở mục vai trò nghĩa đúng như chữ: bạn đăng nhập được và không tới
được gì cả.

## 6. Tổng quan

Màn hình mặc định. Nó trả lời câu hỏi "chúng ta đang ở đâu" trong phạm vi của bạn:

- các chỉ số chính, mỗi chỉ số hiển thị bằng chữ thay vì chữ số khi tập hợp phía sau chưa được đo;
- xu hướng mười hai tuần giữa số phát hiện mới mở và số phát hiện đã đóng;
- thanh độ bao phủ — bao nhiêu phần hạ tầng trong tầm nhìn đã thực sự được đo;
- phân bố mức nghiêm trọng của những gì đang mở;
- các phát hiện được ghi nhận gần đây nhất.

Xu hướng là con số đáng đọc trước tiên. Số đóng liên tục thấp hơn số mở nghĩa là tồn đọng đang tăng,
bất kể tổng số nói gì.

Mỗi khối đều dẫn tới các dòng dữ liệu phía sau. Khi bạn bấm vào, nền tảng kiểm tra lại quyền của bạn
với từng bản ghi; tới được một biểu đồ không đồng nghĩa với được xem các bản ghi mà nó tổng hợp.

## 7. Phát hiện và lỗ hổng

**Lỗ hổng** liệt kê mọi phát hiện trong phạm vi của bạn, có bộ lọc theo mức nghiêm trọng, trạng thái
và tài sản bị ảnh hưởng, và xuất được ra bảng tính. **Pipeline** là cùng tập phát hiện đó nhưng thu
hẹp vào những gì đến từ quét tự động thay vì từ một đợt đánh giá — cùng quyền, khác đối tượng đọc.

### Vòng đời của một phát hiện

Một phát hiện đi qua sáu tình huống. Chúng tách rời nhau vì gộp bất kỳ hai tình huống nào cũng làm mất
một dữ kiện mà ai đó cần:

1. **Mở** — đã ghi nhận, chưa ai nhận khắc phục.
2. **Đã sửa** — đội phát triển nói đã xong và chưa ai kiểm chứng. Đây là một *tuyên bố*, không phải
   một kết luận.
3. **Đã đề nghị chấp nhận** — có người đề nghị để nguyên điểm yếu và chưa ai phê duyệt. Trạng thái này
   vẫn tính là công việc đang mở. Nếu việc đề nghị được tính là đã đóng thì cách nhanh nhất để làm
   sạch tồn đọng sẽ là đề nghị chấp nhận rồi không bao giờ phê duyệt.
4. **Đã đóng** — có người kiểm chứng và đúng là đã sửa.
5. **Mở lại** — có người kiểm chứng và *chưa* sửa. Cố ý tách khỏi "Mở": "đã báo là sửa nhưng không
   phải" là dữ kiện đáng đếm, và trả nó về "Mở" sẽ xóa mất tín hiệu duy nhất cho biết một lần kiểm
   chứng lại đã thất bại.
6. **Chấp nhận rủi ro** — cố ý để nguyên, cho tới một ngày đã nêu. Một sự chấp nhận không có điểm kết
   thúc thì không phải là chấp nhận, nên ngày kết thúc là bắt buộc.

### Ai được thực hiện bước nào

- **Nhận khắc phục** dành cho người sở hữu việc khắc phục. Đây không phải hành động đặc quyền, và việc
  đề nghị chấp nhận rủi ro dùng cùng một quyền — người sở hữu công việc cũng là người đề nghị không
  làm nó.
- **Kiểm chứng** — đóng hoặc mở lại — là một quyền riêng, thuộc về người đã đánh giá. Đội bị đánh giá
  không tự đóng phát hiện của mình; một nền tảng cho phép điều đó thì không đo được gì.
- **Phê duyệt chấp nhận rủi ro** là quyền bị hạn chế, cần mã xác thực mới, và **phải là người khác với
  người đề nghị.** Điều này được ràng buộc ở tầng cơ sở dữ liệu chứ không chỉ kiểm tra ở giao diện.
  Nếu bạn bị từ chối ở đây, lý do là bạn chính là người đề nghị.

Mỗi bước chuyển được ghi thành một dòng riêng với người thực hiện, thời điểm và lý do. Bản ghi đó
không sửa được.

### Đọc một phát hiện

Phần mô tả và bằng chứng khai thác được kết xuất ở máy chủ, không lắp ráp trong trình duyệt của bạn.
Nội dung phát hiện đương nhiên chứa văn bản do kẻ tấn công viết — bằng chứng khai thác chính là như
vậy — nên nó được lưu ở dạng Markdown và kết xuất qua một tập con nghiêm ngặt. HTML thô bị thoát ký tự
và hiển thị dưới dạng văn bản. Liên kết theo lược đồ khác `http` và `https` không phải là liên kết.
Ảnh theo địa chỉ URL không được kết xuất, vì một thẻ ảnh trỏ tới máy chủ của người khác sẽ báo cho họ
biết ai đã mở phát hiện và vào lúc nào; bằng chứng đi qua đường đính kèm, nơi kiểu tệp được kiểm tra.

Nếu một trường bạn mong đợi đơn giản là *không có mặt* thay vì bị che, đó là quy tắc ở mức trường: giá
trị bị hạn chế mà bạn không được đọc sẽ bị *loại bỏ* khỏi phản hồi chứ không thay bằng dấu chấm. Một
hàng dấu chấm sẽ xác nhận rằng có tồn tại một giá trị — với một bí mật đã bị lộ, điều đó xác nhận rằng
có thông tin xác thực nằm đúng chỗ đó.

## 8. Bảng yêu cầu đánh giá

Bảng là nơi các yêu cầu công việc an toàn phần mềm tồn tại và dịch chuyển. Mỗi thẻ là một yêu cầu.

### Tạo yêu cầu

Dùng **Yêu cầu mới** từ bảng. Bạn nêu dự án liên quan, mô tả điều bạn cần, rồi gửi.

Bạn cần nhiều hơn là quyền nhìn thấy để tạo yêu cầu. Nhìn thấy một dự án cho nền tảng biết bạn được
đọc nó; yêu cầu công việc trên nó lại chiếm thời gian của người khác và đưa tài sản vào diện kiểm thử.
Bạn cần hoặc quyền thực hiện đánh giá — đội an toàn thông tin có quyền này, để việc kiểm chứng lại và
công việc phát sinh từ sự cố không bị chặn — hoặc được ghi nhận là người sở hữu hay người được ủy
quyền của đúng dự án đó. Người sở hữu một dự án có thể ủy quyền yêu cầu công việc trên dự án của mình
mà không cần bất kỳ quyền quản trị toàn nền tảng nào.

### Luồng công việc

Yêu cầu đi qua luồng công việc do tổ chức bạn định nghĩa. Cấu hình mặc định đi kèm chạy như sau: nháp,
đã gửi, xem xét tiếp nhận, chấp nhận hoặc trả lại để bổ sung thông tin, đã lên lịch, đã phân công,
đang thực hiện (có trạng thái tạm dừng), kiểm thử xong, dự thảo báo cáo, báo cáo đang kiểm định chất
lượng, đã bàn giao báo cáo, đang khắc phục, kiểm chứng lại, và một trong ba trạng thái kết thúc — đóng
đạt, đóng với rủi ro được chấp nhận, hoặc đã hủy.

Hai đặc tính của luồng này đáng biết vì chúng sẽ từ chối bạn ở đâu đó:

- **Mỗi bước chuyển cần quyền riêng.** Gửi, phân loại, lên lịch, thực hiện và phê duyệt báo cáo ở khâu
  kiểm định là năm quyền khác nhau, và không vai trò nào buộc phải giữ cả năm.
- **Người phê duyệt báo cáo ở khâu kiểm định phải khác tác giả.** Ngược lại bước chuyển bị từ chối,
  kèm một câu giải thích thay vì tên một ràng buộc.

Bảng cho bạn thấy những bước nào khả dụng trên từng yêu cầu, và khi một bước không khả dụng thì vì sao.

### Làm việc trên một yêu cầu

Mở một yêu cầu để xem trạng thái, người được phân công, hạn hoàn thành, các phát hiện ghi nhận được,
những người tham gia và các bình luận.

**Người tham gia** là những người phía triển khai: các lập trình viên thực sự làm việc. Thêm họ vào và
họ đọc được yêu cầu cùng các phát hiện, bình luận được, và nhận khắc phục được — nhưng không đóng được
phát hiện. Đội an toàn thông tin, người sở hữu dự án mà yêu cầu nêu tên, và người tạo yêu cầu đều quản
lý được danh sách này. Người tạo yêu cầu được đưa vào vì thường chỉ họ mới biết lập trình viên nào
đang làm.

**Ghi nhận một phát hiện** trên yêu cầu cần quyền phân loại. Trình soạn thảo là trình soạn thảo văn
bản có định dạng nhưng lưu ở dạng Markdown, với thanh công cụ cố ý giới hạn đúng những gì bộ kết xuất
hỗ trợ — một nút tạo ra định dạng mà máy chủ sau đó loại bỏ sẽ âm thầm làm mất công sức của bạn. Ảnh
dán vào đi qua đường đính kèm và phải là ảnh raster; máy chủ kiểm tra chuỗi byte chứ không tin tên tệp.

**Bình luận là vĩnh viễn.** Một bình luận có thể được che đi kèm dấu vết cho thấy điều đó đã xảy ra,
bởi người có quyền tương ứng; nó không thể bị âm thầm xóa.

## 9. Ứng dụng, dự án và thành phần

**Ứng dụng** là danh mục những gì tổ chức bạn vận hành. Mỗi mục mang tên gọi, nút tổ chức chịu trách
nhiệm, mức trọng yếu, mức phơi bày, trạng thái vòng đời và điểm rủi ro hiện tại. Lọc theo bất kỳ tiêu
chí nào trong số đó; sắp xếp theo bất kỳ tiêu chí nào.

Mở một ứng dụng cho bạn hồ sơ của nó, cấu tạo kỹ thuật, các phát hiện gộp theo mức nghiêm trọng, các
yêu cầu đánh giá và chu kỳ rà soát.

**Dự án** là nhánh của một ứng dụng do một đội cụ thể bàn giao. Ứng dụng của một dự án được suy ra
bằng cách đi ngược đồ thị cấu tạo tới ứng dụng gần nhất, chứ không lưu trên bản ghi dự án — nên việc
chuyển một dự án không để lại câu trả lời cũ. Đây cũng là điều cho phép biểu mẫu tiếp nhận chỉ cần hỏi
tên dự án.

**Thành phần** là hạ tầng kỹ thuật ở mức chi tiết hơn: các tính năng và dịch vụ tạo nên một ứng dụng.

**Chỉnh sửa.** Tạo và cập nhật một ứng dụng hay thành phần là thao tác ghi thông thường trong phạm vi
và không hỏi yếu tố thứ hai. Đặt một lời nhắc nhập mã trước việc thêm một địa chỉ môi trường thử
nghiệm là kiểm soát kích hoạt trên công việc thường ngày, và kiểm soát kiểu đó là loại người ta tìm
cách đi vòng. Ngừng sử dụng một mục cũng là thao tác ghi thông thường; bản ghi vẫn còn và lịch sử
không bao giờ bị xóa.

**Tổ chức** hiển thị chính cây phân cấp — các nút, loại nút, nút cha, mức trọng yếu và các tài sản gắn
vào từng nút. Thay đổi cây phân cấp *là* cấu hình: nó quyết định ai thấy được gì, nên nó hỏi yếu tố
thứ hai và được ghi vết kèm trạng thái trước và sau.

## 10. Thành phần phụ thuộc và cấu tạo phần mềm

**Thành phần phụ thuộc** trả lời câu hỏi "chúng ta đang đóng gói những gì, và phần nào trong đó có lỗ
hổng". Màn hình trình bày các thành phần đang dùng, nơi từng thành phần được dùng, các cảnh báo ảnh
hưởng tới chúng, và đồ thị phụ thuộc.

**Cấu tạo** hiển thị độ bao phủ: mọi tài sản, bao gồm — và đây là điểm quan trọng — cả những tài sản
chưa từng gửi lên bản kê khai nào. Một tài sản vắng mặt trong báo cáo phụ thuộc không phải là tài sản
không có thành phần phụ thuộc.

### Dữ liệu tới đây bằng cách nào

Một quy trình dựng phần mềm gửi bản kê khai thành phần phần mềm lên điểm tiếp nhận của nền tảng, dùng
thông tin xác thực cấp từ **Cài đặt**. Đường đẩy dữ liệu đó là con đường nạp tự động duy nhất. Nền
tảng không tải về, không sao chép và không quét mã nguồn, và không lưu thông tin xác thực kho mã — nên
không có gì ở đây phụ thuộc vào việc cấp cho nền tảng quyền truy cập mã của bạn.

Bạn cũng có thể tải bản kê khai lên thủ công từ trang của một hiện vật. Nó đi qua đúng cùng đoạn mã
nạp dữ liệu như đường quy trình tự động, nên hai đường không thể mang hai ý nghĩa khác nhau.

**Tình trạng gửi dữ liệu** báo cáo theo từng thông tin xác thực xem dữ liệu còn được gửi tới hay
không. Một quy trình đã âm thầm dừng từ sáu tuần trước chính là kiểu hỏng mà mục này tồn tại để phát
hiện: độ bao phủ suy giảm, không nơi nào báo lỗi, và bảng điều khiển tiếp tục hiển thị số liệu cuối
cùng nó có. Mục này mở cho bất kỳ ai đọc được độ bao phủ, không riêng người quản lý thông tin xác thực
— người cần biết một tích hợp đã dừng chính là người chịu trách nhiệm con số mà nó nuôi.

## 11. Khối lượng công việc và kế hoạch

**Khối lượng công việc** cho thấy công việc đánh giá đang chảy ra sao: số lượng theo trạng thái, thời
gian mỗi giai đoạn, và hàng đợi công việc đang chờ cùng lý do chờ.

**Phân bổ theo từng người là một quyền riêng.** Đọc số liệu tổng hợp của cả đội và đọc khối lượng việc
của từng cá nhân có tên là hai hành động khác nhau với hệ quả khác nhau, và hành động thứ hai bị hạn
chế, được ghi vết, và không bao giờ suy ra từ thâm niên. Nơi bạn không có quyền đó, phần theo từng
người *không có mặt* trên màn hình chứ không hiển thị rỗng.

Số liệu tổng hợp trên nhóm quá nhỏ bị ẩn đi thay vì hiển thị. Trong một đội ba người mà bạn thấy được
hai, khối lượng của người thứ ba chỉ cách một phép trừ — nên nền tảng từ chối làm phép trừ đó.

**Kế hoạch** trả lời câu hỏi "toàn hạ tầng còn nợ những gì và tới hạn khi nào". Chu kỳ rà soát cấu
hình cho từng bậc trọng yếu quyết định khi nào một ứng dụng tới hạn đánh giá lại, và ngày tới hạn kế
tiếp được suy ra chứ không lưu sẵn — đó là lý do việc nới rộng chu kỳ được coi là cấu hình và cần yếu
tố thứ hai. Nới rộng nó khiến một phần hạ tầng thôi quá hạn một cách hồi tố.

## 12. Cài đặt

Phần cấu hình quyết định nền tảng hành xử thế nào, tách biệt với việc ai được dùng nền tảng.

**Nhà cung cấp AI.** Nền tảng được phép gửi nội dung tới nhà cung cấp mô hình nào, và thông tin xác
thực tương ứng. Đây là quyết định dữ liệu nào của tổ chức được rời khỏi ranh giới của bạn, nên cả hai
thao tác ghi đều cần yếu tố thứ hai và thông tin xác thực không bao giờ đọc lại được sau khi nhập —
không ở mức quyền nào cả. Phần đọc của màn hình này không mang bất kỳ phần nào của khóa.

**Cảnh báo.** Các đích webhook được thông báo khi một cảnh báo mới ảnh hưởng tới thứ bạn đang đóng gói.

**Lịch quét lại.** Tần suất kết quả quét theo lịch được lấy về và gửi lên.

**Chính sách rà soát.** Chu kỳ đánh giá lại theo từng bậc trọng yếu. Xem ghi chú ở mục 11 về lý do
phần này hỏi yếu tố thứ hai.

**Thông tin xác thực dịch vụ.** Định danh dành cho quy trình dựng phần mềm. Khi cấp, bí mật hiển thị
đúng một lần — không có đường nào lấy lại sau đó. Thu hồi có hiệu lực ngay. Thông tin xác thực này
ràng buộc theo bên gửi chứ không phải một mã mang theo được, nên tích hợp là một bước ký chứ không
phải một tiêu đề bạn sao chép.

## 13. Trợ giúp bằng AI

Nền tảng có thể soạn thảo, phân loại và tóm tắt. Nó không quyết định.

**Mọi thứ mô hình tạo ra đều rơi vào sổ đề xuất.** Một đề xuất là một kiến nghị kèm cơ sở của nó: nó
được rút ra từ những bản ghi nào. Không có gì mô hình viết ra tự đi vào hồ sơ chính thức.

**Việc chấp nhận là hành động của con người và được ghi vết.** Khi bạn chấp nhận một đề xuất, nền tảng
kiểm tra lại rằng *bạn* có quyền thực hiện thay đổi tương ứng. Một đề xuất không thể làm được điều mà
tự bạn không làm được.

**Con số không bao giờ được sinh ra bởi mô hình.** Khi một đoạn diễn giải có chứa con số, con số đó
được gắn với một trường trên bản ghi chứ không do mô hình tạo. Điểm rủi ro, hạn mức thời gian dịch vụ,
quyết định gộp trùng và quyết định phân quyền đều được tính một cách tất định và tái lập được; chạy
lại cùng một phép tính hai lần cho ra cùng một kết quả hai lần.

Nút **Phân tích** trên các bảng điều khiển yêu cầu một năng lực đã bật xem xét màn hình trước mặt bạn.
Việc yêu cầu được kiểm soát bằng quyền hành động trên kết quả, không phải bằng quyền quản trị đã bật
năng lực đó.

## 14. Truy cập và vai trò

Hai màn hình, cố ý tách riêng, vì "ai được dùng nền tảng" và "một vai trò nghĩa là gì" là hai công
việc khác nhau, thường do hai người khác nhau nắm. Một màn hình phục vụ cả hai chính là cách một người
đang tra cứu quyền của đồng nghiệp lại kết thúc ở việc sửa một lưới quyền.

### Truy cập — con người và quyền được cấp

Liệt kê những người trong phạm vi của bạn và những gì mỗi người đang giữ. Mở một người ra sẽ thấy các
quyền được cấp, cho phép thêm hoặc thu hồi, và cho phép cấp một lệnh đặt lại thông tin xác thực.

**Cấp một vai trò nghĩa là chọn hai thứ:** vai trò, và phạm vi nó áp dụng.

- **Toàn tổ chức** — toàn bộ tổ chức. Không nêu tên nút nào.
- **Cây con** — nút bạn chọn và mọi thứ bên dưới nó.
- **Chỉ một nút** — đúng nút bạn chọn.

Một lần cấp có thể kèm thời hạn. Việc thu hồi ghi lại ai thu hồi và vì sao; nó không xóa lịch sử những
gì người đó đã làm trong thời gian nắm giữ.

**Đặt lại thông tin xác thực** cấp một liên kết dùng một lần, thu hồi các phiên đang hoạt động của
người đó và buộc đổi mật khẩu. Thao tác này bị hạn chế và cần yếu tố thứ hai, vì liên kết nó tạo ra
trong thời gian ngắn chính là một lối vào tài khoản.

### Vai trò — một vai trò nghĩa là gì

Vai trò là một tập quyền có tên. Bản thân danh mục quyền do sản phẩm cố định — bạn ghép từ đó chứ
không thêm vào đó — vì một quyền không có gì thực thi phía sau sẽ trông như đang bảo vệ mà thực chất
không bảo vệ gì.

Vai trò là của bạn. Đổi tên, tách, gộp, ngừng dùng, xóa những vai trò bạn không cần. Những tên đi kèm
một triển khai mới chỉ là điểm khởi đầu để nền tảng dùng được ngay ngày đầu, không phải khái niệm của
sản phẩm; không có gì trong nền tảng hành xử khác đi vì một vai trò mang tên này thay vì tên kia.

Vài điều đáng biết trước khi soạn một vai trò:

- **Đọc và xuất luôn là hai quyền tách rời.** Đọc một phát hiện và rút ra năm mươi nghìn phát hiện là
  hai hành động khác nhau với mức rủi ro khác nhau.
- **Quyền bị hạn chế không bao giờ được suy ra.** Xem một bí mật đã bị lộ, đọc khối lượng việc theo
  từng người, và xem thông tin xác thực kiểm thử — mỗi thứ cần được cấp riêng một cách có chủ ý. Không
  mức thâm niên hay năng lực quản trị nào tự động đem lại chúng.
- **Quyền mới không bao giờ tự được thêm vào vai trò sẵn có.** Khi nền tảng có thêm năng lực, chưa ai
  có nó cho tới khi được cấp. Đó là mặc định đúng, dù nó có nghĩa là cần một bước sau mỗi lần nâng cấp.
- **Quản lý vai trò tách khỏi mọi quyền vận hành.** Người vừa hành động vừa cấp quyền có thể tự cấp
  cho mình bất cứ thứ gì, khiến mọi ràng buộc khác chỉ còn tính khuyến nghị.
- **Vai trò đang được sử dụng thì không xóa được.** Hãy ngừng dùng nó; các lần cấp tham chiếu tới nó
  vẫn giữ nguyên ý nghĩa.

**Chính sách thông tin xác thực** là màn hình thứ ba trong nhóm cấu hình: độ dài mật khẩu, số lần
không cho dùng lại, bật hay tắt kiểm tra mật khẩu đã lộ, thời hạn phiên, và yếu tố thứ hai có bắt buộc
với mọi người hay không. Màn hình này cũng báo kích thước kho mật khẩu đã lộ đang nạp, để một kho quá
mỏng hiện ra rõ ràng thay vì trông như một phép kiểm tra đang hoạt động.

## 15. Khi một thao tác bị từ chối

Bốn kiểu từ chối, và chúng có nghĩa khác nhau.

**Màn hình không có trong thanh điều hướng của bạn.** Bạn không giữ quyền mà màn hình đó yêu cầu.
Không có gì hỏng; hãy đề nghị cấp quyền nếu bạn cần.

**"Không tìm thấy" với thứ bạn tin là có tồn tại.** Hoặc nó không tồn tại, hoặc nó tồn tại ngoài phạm
vi của bạn. Nền tảng sẽ không cho bạn biết là trường hợp nào — xem mục 5.

**Bạn bị hỏi lại mã xác thực.** Thao tác thuộc nhóm nêu ở mục 3. Nhập mã và bạn được đưa trở lại đúng
chỗ đang làm dở.

**Một hành động bị từ chối kèm một câu giải thích.** Một điều kiện của luồng công việc, một quy tắc
phân tách trách nhiệm, hoặc một điều kiện tiên quyết còn thiếu. Câu đó nói rõ là điều nào; hãy xử lý
theo nó thay vì thử lại.

Nếu một trang hỏng hẳn thay vì từ chối, đó là lỗi và nó cố ý ồn ào. Nền tảng được xây để hỏng một cách
nhìn thấy được thay vì suy giảm âm thầm thành việc đưa cho bạn một câu trả lời hợp lý nhưng sai.

## 16. Khả năng tiếp cận và ngôn ngữ

Giao diện hoạt động ở mức phóng to 200% mà không mất chức năng. Màu sắc không bao giờ là thứ duy nhất
mang ý nghĩa — mọi trạng thái có màu đều kèm nhãn hoặc hình dạng. Mọi chức năng đều tới được bằng bàn
phím, và mọi chức năng đều hoạt động khi không bật JavaScript, dù một số sẽ thuận tiện hơn khi có.

Ngôn ngữ gốc là tiếng Anh và tiếng Việt là ngôn ngữ đích đầu tiên. Ngày tháng và số được định dạng
theo ngôn ngữ của bạn; thời gian lưu theo UTC và hiển thị theo lịch làm việc của tổ chức bạn — đó là
điều khiến một hạn hoàn thành mang cùng một nghĩa với mọi người đọc nó.

## 17. Bản dựng này chưa làm được gì

Nói thẳng, vì một khoảng trống bạn tự phát hiện khi đang dùng thì tệ hơn một khoảng trống được báo
trước.

- **Tìm kiếm theo mã đối tượng chưa được xây dựng.** Bảng lệnh điều hướng tới các mục; nó không tìm
  bản ghi.
- **Phân quyền theo lịch sử chưa được xây dựng.** Câu hỏi "ai thấy được thứ này hồi tháng Ba" chưa trả
  lời được, và nền tảng từ chối câu hỏi thay vì trả lời nó dựa trên sơ đồ tổ chức của hôm nay.
- **Quét kho ảnh vùng chứa được hoãn lại.** Các điểm mở rộng đã có sẵn và bị từ chối ở tầng ứng dụng
  thay vì hoạt động nửa vời.
- **Một số màn hình quản trị vẫn được kết xuất ở máy chủ** và trông hơi khác phần còn lại. Đăng nhập,
  thử thách yếu tố thứ hai, đổi thông tin xác thực và lời nhắc xác thực nâng bậc cố ý nằm trong nhóm
  đó: tính đúng đắn của chúng nằm ở các bước chuyển hướng phía máy chủ, và một rào chắn được cài ở hai
  nơi là rào chắn có thể tự mâu thuẫn với chính nó.
