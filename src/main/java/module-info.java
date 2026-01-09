module com.grabx.app.grabx {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;

    // Current controller package
    opens com.grabx.app.grabx to javafx.fxml;

    // Future: if controllers move under ui package
    opens com.grabx.app.grabx.ui to javafx.fxml;

    exports com.grabx.app.grabx;

}